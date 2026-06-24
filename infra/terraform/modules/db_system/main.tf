data "oci_identity_availability_domains" "ads" {
  compartment_id = var.compartment_ocid
}

resource "oci_database_db_system" "this" {
  availability_domain = data.oci_identity_availability_domains.ads.availability_domains[0].name
  compartment_id      = var.compartment_ocid
  subnet_id           = var.private_subnet_id
  nsg_ids             = [var.db_nsg_id]

  shape            = var.db_shape
  cpu_core_count   = 2
  node_count       = 2
  database_edition = "ENTERPRISE_EDITION_EXTREME_PERFORMANCE"
  cluster_name     = "cmanrac"
  hostname         = "cmanrac"
  display_name     = "cman-poc-db"
  ssh_public_keys  = [var.ssh_public_key]

  data_storage_size_in_gb = 256
  db_system_options { storage_management = "ASM" }

  db_home {
    db_version   = var.db_version
    display_name = "cman-poc-dbhome"
    database {
      admin_password      = var.db_admin_password
      db_name             = var.db_name
      pdb_name            = "${var.db_name}pdb"
      tde_wallet_password = var.db_admin_password
      character_set       = "AL32UTF8"
      ncharacter_set      = "AL16UTF16"
    }
  }
}
