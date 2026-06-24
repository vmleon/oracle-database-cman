terraform {
  required_providers {
    oci = {
      source = "oracle/oci"
    }
  }
}

variable "compartment_ocid" {
  type = string
}

variable "private_subnet_id" {
  type = string
}

variable "db_nsg_id" {
  type = string
}

variable "ssh_public_key" {
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

variable "db_shape" {
  type    = string
  default = "VM.Standard2.2"
}
