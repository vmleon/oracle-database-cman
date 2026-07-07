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

variable "tdm_password" {
  type        = string
  sensitive   = true
  default     = ""
  description = "Password for the TDM proxy DB user; stored in the CMAN wallet for proxy authentication"
}

variable "appuser_password" {
  type        = string
  sensitive   = true
  default     = ""
  description = "Password for the application DB user the client connects as through CMAN"
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

variable "cman_client_source_path" {
  type        = string
  description = "Local path to the Oracle 19c client Administrator zip; uploaded to the artifacts bucket by setup"
}

variable "db_node_count" {
  type        = number
  default     = 2
  description = "DB system nodes: 2 = RAC (needed for draining/upgrade demos), 1 = single instance (much faster to provision)"
}
