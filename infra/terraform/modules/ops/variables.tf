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

variable "tenancy_ocid" {
  type = string
}

variable "region" {
  type = string
}

variable "public_subnet_id" {
  type = string
}

variable "ops_nsg_id" {
  type = string
}

variable "ssh_public_key" {
  type = string
}

variable "ssh_private_key_path" {
  type = string
}

variable "vm_shape" {
  type    = string
  default = "VM.Standard.E5.Flex"
}

variable "ansible_par_url" {
  type = string
}

variable "cman_client_par_url" {
  type      = string
  sensitive = true
}

variable "cman_private_ip" {
  type = string
}

variable "cman_public_ip" {
  type = string
}

variable "db_node_private_ip" {
  type = string
}

variable "db_admin_password" {
  type      = string
  sensitive = true
}

variable "tdm_password" {
  type      = string
  sensitive = true
}

variable "appuser_password" {
  type      = string
  sensitive = true
}

variable "db_name" {
  type = string
}

variable "service_name" {
  type = string
}

variable "client_cidr" {
  type = string
}
