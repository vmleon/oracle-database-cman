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

variable "public_subnet_id" {
  type = string
}

variable "cman_nsg_id" {
  type = string
}

variable "ssh_public_key" {
  type = string
}

variable "vm_shape" {
  type    = string
  default = "VM.Standard.E5.Flex"
}
