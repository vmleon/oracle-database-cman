data "oci_identity_availability_domains" "ads" {
  compartment_id = var.tenancy_ocid
}

data "oci_core_images" "ol9" {
  compartment_id           = var.compartment_ocid
  operating_system         = "Oracle Linux"
  operating_system_version = "9"
  sort_by                  = "TIMECREATED"
  sort_order               = "DESC"

  filter {
    name   = "display_name"
    values = ["^Oracle-Linux-9\\.[0-9]+-[0-9]{4}\\.[0-9]{2}\\.[0-9]{2}-[0-9]+$"]
    regex  = true
  }
}

locals {
  cloud_init = templatefile("${path.module}/userdata/bootstrap.tftpl", {
    region              = var.region
    ansible_par_url     = var.ansible_par_url
    cman_client_par_url = var.cman_client_par_url
    private_key_content = file(var.ssh_private_key_path)
    cman_private_ip     = var.cman_private_ip
    cman_public_ip      = var.cman_public_ip
    db_node_private_ip  = var.db_node_private_ip
    db_password         = var.db_admin_password
    tdm_password        = var.tdm_password
    appuser_password    = var.appuser_password
    db_name             = var.db_name
    service_name        = var.service_name
  })
}

resource "oci_core_instance" "ops" {
  compartment_id      = var.compartment_ocid
  availability_domain = data.oci_identity_availability_domains.ads.availability_domains[0].name
  display_name        = "cman-poc-ops"
  shape               = var.vm_shape

  shape_config {
    ocpus         = 1
    memory_in_gbs = 16
  }

  create_vnic_details {
    subnet_id        = var.public_subnet_id
    assign_public_ip = true
    nsg_ids          = [var.ops_nsg_id]
    hostname_label   = "cmanops"
  }

  source_details {
    source_type = "image"
    source_id   = data.oci_core_images.ol9.images[0].id
  }

  metadata = {
    ssh_authorized_keys = var.ssh_public_key
    user_data           = base64encode(local.cloud_init)
  }

  # Keep the provisioned host's image stable: a newer OL9 published by OCI drifts source_id and
  # triggers an in-place update (which also trips OCI's 50 GB boot-volume minimum). Rebuild on a
  # fresh image deliberately with -replace when needed.
  lifecycle {
    ignore_changes = [source_details[0].source_id]
  }
}
