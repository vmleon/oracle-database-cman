output "vcn_id" {
  value = oci_core_vcn.this.id
}

output "public_subnet_id" {
  value = oci_core_subnet.public.id
}

output "private_subnet_id" {
  value = oci_core_subnet.private.id
}

output "cman_nsg_id" {
  value = oci_core_network_security_group.cman.id
}

output "db_nsg_id" {
  value = oci_core_network_security_group.db.id
}

output "ops_nsg_id" {
  value = oci_core_network_security_group.ops.id
}
