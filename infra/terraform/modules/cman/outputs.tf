output "cman_public_ip" {
  value = oci_core_instance.cman.public_ip
}

output "cman_private_ip" {
  value = oci_core_instance.cman.private_ip
}
