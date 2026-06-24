module "network" {
  source           = "./modules/network"
  compartment_ocid = var.compartment_ocid
  client_cidr      = var.client_cidr
}

module "db_system" {
  source            = "./modules/db_system"
  compartment_ocid  = var.compartment_ocid
  private_subnet_id = module.network.private_subnet_id
  db_nsg_id         = module.network.db_nsg_id
  ssh_public_key    = var.ssh_public_key
  db_admin_password = var.db_admin_password
  db_name           = var.db_name
  db_version        = var.db_version
  db_shape          = var.db_shape
}

module "cman" {
  source           = "./modules/cman"
  compartment_ocid = var.compartment_ocid
  tenancy_ocid     = var.tenancy_ocid
  public_subnet_id = module.network.public_subnet_id
  cman_nsg_id      = module.network.cman_nsg_id
  ssh_public_key   = var.ssh_public_key
  vm_shape         = var.vm_shape
}
