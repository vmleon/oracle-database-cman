variable "oci_profile" {
  type    = string
  default = "DEFAULT"
}

variable "region" {
  type = string
}

variable "tenancy_ocid" {
  type = string
}

variable "user_ocid" {
  type = string
}

variable "fingerprint" {
  type = string
}

variable "private_key_path" {
  type = string
}

variable "compartment_ocid" {
  type = string
}

variable "client_cidr" {
  type        = string
  description = "Operator/client public egress CIDR (e.g. 203.0.113.10/32)"
}

variable "ssh_public_key" {
  type = string
}

variable "ssh_private_key_path" {
  type = string
}

variable "db_admin_password" {
  type      = string
  sensitive = true
}

variable "db_name" {
  type    = string
  default = "dbcman"
}

variable "db_version" {
  type    = string
  default = "19.0.0.0"
}

variable "vm_shape" {
  type    = string
  default = "VM.Standard.E5.Flex"
}

variable "db_shape" {
  type    = string
  default = "VM.Standard2.2"
}

variable "cman_client_object" {
  type        = string
  default     = "client.zip"
  description = "Object name of the staged Oracle 19c client Administrator installer in the artifacts bucket"
}
