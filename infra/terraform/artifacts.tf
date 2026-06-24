data "oci_objectstorage_namespace" "ns" {
  compartment_id = var.compartment_ocid
}

data "archive_file" "ansible" {
  type        = "zip"
  source_dir  = "${path.module}/../../ansible"
  output_path = "${path.module}/generated/ansible.zip"
}

resource "oci_objectstorage_bucket" "artifacts" {
  compartment_id = var.compartment_ocid
  namespace      = data.oci_objectstorage_namespace.ns.namespace
  name           = "cman-poc-artifacts"
  access_type    = "NoPublicAccess"
}

resource "oci_objectstorage_object" "ansible" {
  bucket      = oci_objectstorage_bucket.artifacts.name
  namespace   = data.oci_objectstorage_namespace.ns.namespace
  object      = "ansible.zip"
  source      = data.archive_file.ansible.output_path
  content_md5 = data.archive_file.ansible.output_md5
}

resource "time_static" "deploy" {}

resource "oci_objectstorage_preauthrequest" "ansible" {
  namespace    = data.oci_objectstorage_namespace.ns.namespace
  bucket       = oci_objectstorage_bucket.artifacts.name
  name         = "cman-poc-ansible-par"
  access_type  = "ObjectRead"
  object_name  = oci_objectstorage_object.ansible.object
  time_expires = timeadd(time_static.deploy.rfc3339, "168h")
}

# Operator stages the Oracle 19c client Administrator zip into the bucket once.
# This PAR lets the cman role pull it during provisioning.
resource "oci_objectstorage_preauthrequest" "client" {
  namespace    = data.oci_objectstorage_namespace.ns.namespace
  bucket       = oci_objectstorage_bucket.artifacts.name
  name         = "cman-poc-client-par"
  access_type  = "ObjectRead"
  object_name  = var.cman_client_object
  time_expires = timeadd(time_static.deploy.rfc3339, "168h")
}
