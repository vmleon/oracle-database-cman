resource "oci_core_vcn" "this" {
  compartment_id = var.compartment_ocid
  cidr_blocks    = [var.vcn_cidr]
  display_name   = "cman-poc-vcn"
  dns_label      = "cmanpoc"
}

resource "oci_core_default_security_list" "default" {
  manage_default_resource_id = oci_core_vcn.this.default_security_list_id
  compartment_id             = var.compartment_ocid
  display_name               = "cman-poc-default-sl"

  egress_security_rules {
    destination = "0.0.0.0/0"
    protocol    = "all"
  }
  # no ingress rules: all ingress is governed by NSGs (client-CIDR allowlist)
}

# Private (DB) subnet security list. OCI's LaunchDbSystem validates the subnet's
# security list (not NSGs) and requires port 22 ingress; the NSGs still enforce the
# fine-grained allowlist.
resource "oci_core_security_list" "private" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.this.id
  display_name   = "cman-poc-private-sl"

  egress_security_rules {
    destination = "0.0.0.0/0"
    protocol    = "all"
  }

  ingress_security_rules {
    source   = var.vcn_cidr
    protocol = "6"
    tcp_options {
      min = 22
      max = 22
    }
  }
}

resource "oci_core_internet_gateway" "igw" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.this.id
  display_name   = "cman-poc-igw"
}

resource "oci_core_route_table" "public" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.this.id
  display_name   = "cman-poc-public-rt"
  route_rules {
    destination       = "0.0.0.0/0"
    network_entity_id = oci_core_internet_gateway.igw.id
  }
}

# Service Gateway so the private DB subnet can reach Object Storage (LaunchDbSystem
# requires it) without a public route.
data "oci_core_services" "oci_services" {
  filter {
    name   = "name"
    values = ["All .* Services In Oracle Services Network"]
    regex  = true
  }
}

resource "oci_core_service_gateway" "sgw" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.this.id
  display_name   = "cman-poc-sgw"
  services {
    service_id = data.oci_core_services.oci_services.services[0].id
  }
}

resource "oci_core_route_table" "private" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.this.id
  display_name   = "cman-poc-private-rt"
  route_rules {
    destination       = data.oci_core_services.oci_services.services[0].cidr_block
    destination_type  = "SERVICE_CIDR_BLOCK"
    network_entity_id = oci_core_service_gateway.sgw.id
  }
}

resource "oci_core_subnet" "public" {
  compartment_id             = var.compartment_ocid
  vcn_id                     = oci_core_vcn.this.id
  cidr_block                 = "10.0.1.0/24"
  display_name               = "cman-poc-public-subnet"
  route_table_id             = oci_core_route_table.public.id
  prohibit_public_ip_on_vnic = false
  dns_label                  = "pub"
}

resource "oci_core_subnet" "private" {
  compartment_id             = var.compartment_ocid
  vcn_id                     = oci_core_vcn.this.id
  cidr_block                 = "10.0.2.0/24"
  display_name               = "cman-poc-private-subnet"
  prohibit_public_ip_on_vnic = true
  dns_label                  = "priv"
  route_table_id             = oci_core_route_table.private.id
  security_list_ids          = [oci_core_security_list.private.id]
}

resource "oci_core_network_security_group" "cman" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.this.id
  display_name   = "cman-poc-cman-nsg"
}

resource "oci_core_network_security_group" "db" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.this.id
  display_name   = "cman-poc-db-nsg"
  depends_on     = [oci_core_subnet.private]
}

resource "oci_core_network_security_group" "ops" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.this.id
  display_name   = "cman-poc-ops-nsg"
}

# client -> CMAN :1521 (the laptop connects only to CMAN)
resource "oci_core_network_security_group_security_rule" "cman_in_client_1521" {
  network_security_group_id = oci_core_network_security_group.cman.id
  direction                 = "INGRESS"
  protocol                  = "6"
  source                    = var.client_cidr
  source_type               = "CIDR_BLOCK"
  tcp_options {
    destination_port_range {
      min = 1521
      max = 1521
    }
  }
}

# client -> CMAN :22 (operator SSH)
resource "oci_core_network_security_group_security_rule" "cman_in_client_ssh" {
  network_security_group_id = oci_core_network_security_group.cman.id
  direction                 = "INGRESS"
  protocol                  = "6"
  source                    = var.client_cidr
  source_type               = "CIDR_BLOCK"
  tcp_options {
    destination_port_range {
      min = 22
      max = 22
    }
  }
}

# ops -> CMAN :22 (ops provisions CMAN over SSH)
resource "oci_core_network_security_group_security_rule" "cman_in_ops_ssh" {
  network_security_group_id = oci_core_network_security_group.cman.id
  direction                 = "INGRESS"
  protocol                  = "6"
  source                    = oci_core_network_security_group.ops.id
  source_type               = "NETWORK_SECURITY_GROUP"
  tcp_options {
    destination_port_range {
      min = 22
      max = 22
    }
  }
}

# DB -> CMAN :1521 (PMON service registration, so CMAN learns the routed services)
resource "oci_core_network_security_group_security_rule" "cman_in_db_1521" {
  network_security_group_id = oci_core_network_security_group.cman.id
  direction                 = "INGRESS"
  protocol                  = "6"
  source                    = oci_core_network_security_group.db.id
  source_type               = "NETWORK_SECURITY_GROUP"
  tcp_options {
    destination_port_range {
      min = 1521
      max = 1521
    }
  }
}

# CMAN -> DB :1521 (SCAN + node VIP + listener)
resource "oci_core_network_security_group_security_rule" "cman_eg_db_1521" {
  network_security_group_id = oci_core_network_security_group.cman.id
  direction                 = "EGRESS"
  protocol                  = "6"
  destination               = oci_core_network_security_group.db.id
  destination_type          = "NETWORK_SECURITY_GROUP"
  tcp_options {
    destination_port_range {
      min = 1521
      max = 1521
    }
  }
}

# CMAN -> DB :2484 (OCI listeners also advertise a TCPS handler; making it reachable lets
# CMAN fail over quickly to the TCP handler instead of stalling on a silently dropped packet)
resource "oci_core_network_security_group_security_rule" "cman_eg_db_2484" {
  network_security_group_id = oci_core_network_security_group.cman.id
  direction                 = "EGRESS"
  protocol                  = "6"
  destination               = oci_core_network_security_group.db.id
  destination_type          = "NETWORK_SECURITY_GROUP"
  tcp_options {
    destination_port_range {
      min = 2484
      max = 2484
    }
  }
}

# CMAN -> internet :443 (download the staged client installer via PAR)
resource "oci_core_network_security_group_security_rule" "cman_eg_https" {
  network_security_group_id = oci_core_network_security_group.cman.id
  direction                 = "EGRESS"
  protocol                  = "6"
  destination               = "0.0.0.0/0"
  destination_type          = "CIDR_BLOCK"
  tcp_options {
    destination_port_range {
      min = 443
      max = 443
    }
  }
}

# DB ingress 1521 from CMAN
resource "oci_core_network_security_group_security_rule" "db_in_cman_1521" {
  network_security_group_id = oci_core_network_security_group.db.id
  direction                 = "INGRESS"
  protocol                  = "6"
  source                    = oci_core_network_security_group.cman.id
  source_type               = "NETWORK_SECURITY_GROUP"
  tcp_options {
    destination_port_range {
      min = 1521
      max = 1521
    }
  }
}

# DB ingress 22 from ops
resource "oci_core_network_security_group_security_rule" "db_in_ops_ssh" {
  network_security_group_id = oci_core_network_security_group.db.id
  direction                 = "INGRESS"
  protocol                  = "6"
  source                    = oci_core_network_security_group.ops.id
  source_type               = "NETWORK_SECURITY_GROUP"
  tcp_options {
    destination_port_range {
      min = 22
      max = 22
    }
  }
}

# DB intra-cluster: allow all from the DB NSG to itself
resource "oci_core_network_security_group_security_rule" "db_in_self_all" {
  network_security_group_id = oci_core_network_security_group.db.id
  direction                 = "INGRESS"
  protocol                  = "all"
  source                    = oci_core_network_security_group.db.id
  source_type               = "NETWORK_SECURITY_GROUP"
}

# ops ingress 22 from client
resource "oci_core_network_security_group_security_rule" "ops_in_client_ssh" {
  network_security_group_id = oci_core_network_security_group.ops.id
  direction                 = "INGRESS"
  protocol                  = "6"
  source                    = var.client_cidr
  source_type               = "CIDR_BLOCK"
  tcp_options {
    destination_port_range {
      min = 22
      max = 22
    }
  }
}
