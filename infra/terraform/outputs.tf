output "db_name" {
  value = var.db_name
}

output "db_service_name" {
  value = module.db_system.service_name
}

output "db_scan_dns" {
  value = module.db_system.scan_dns
}
