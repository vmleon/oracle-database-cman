output "db_name" {
  value = var.db_name
}

output "db_service_name" {
  value = module.db_system.service_name
}

output "db_scan_dns" {
  value = module.db_system.scan_dns
}

output "cman_public_ip" {
  value = module.cman.cman_public_ip
}

output "ansible_par_url" {
  value = oci_objectstorage_preauthrequest.ansible.full_path
}

output "cman_client_par_url" {
  value     = oci_objectstorage_preauthrequest.client.full_path
  sensitive = true
}
