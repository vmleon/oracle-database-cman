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

module "ops" {
  source               = "./modules/ops"
  compartment_ocid     = var.compartment_ocid
  tenancy_ocid         = var.tenancy_ocid
  region               = var.region
  public_subnet_id     = module.network.public_subnet_id
  ops_nsg_id           = module.network.ops_nsg_id
  ssh_public_key       = var.ssh_public_key
  ssh_private_key_path = var.ssh_private_key_path
  vm_shape             = var.vm_shape
  ansible_par_url      = oci_objectstorage_preauthrequest.ansible.full_path
  cman_client_par_url  = oci_objectstorage_preauthrequest.client.full_path
  cman_private_ip      = module.cman.cman_private_ip
  cman_public_ip       = module.cman.cman_public_ip
  db_node_private_ip   = module.db_system.db_node_private_ip
  db_admin_password    = var.db_admin_password
  db_name              = var.db_name
  service_name         = module.db_system.service_name
  client_cidr          = var.client_cidr
}
