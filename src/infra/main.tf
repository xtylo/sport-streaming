// Infraestructura como código para desplegar servicios en AWS EKS.

provider "aws" {
  region = "us-west-2"
}

resource "aws_eks_cluster" "streaming_cluster" {
  # Configuración del clúster
}

resource "aws_kafka_cluster" "streaming_kafka" {
  # Configuración de Kafka
}