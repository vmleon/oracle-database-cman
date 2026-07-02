data "oci_database_db_nodes" "nodes" {
  compartment_id = var.compartment_ocid
  db_system_id   = oci_database_db_system.this.id
}

data "oci_core_vnic" "node0" {
  vnic_id = data.oci_database_db_nodes.nodes.db_nodes[0].vnic_id
}

output "scan_dns" {
  value = oci_database_db_system.this.scan_dns_name
}

output "db_node_private_ip" {
  value = data.oci_core_vnic.node0.private_ip_address
}

# Informational only — the db role discovers the real unique name at runtime; do not wire this guessed value into any consumer.
output "db_unique_name" {
  value = "${var.db_name}_tp"
}

output "pdb_name" {
  value = "${var.db_name}pdb"
}

output "service_name" {
  # Clients connect through CMAN with the fully-qualified service name: the DB publishes
  # services under its domain, which is the SCAN DNS name after its first label
  # (cmanrac-scan.<domain> -> <domain>).
  value = "myapp.${regex("^[^.]+[.](.+)$", oci_database_db_system.this.scan_dns_name)[0]}"
}
