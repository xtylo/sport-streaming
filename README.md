# Plataforma de Streaming de Eventos

**Problema:** Los eventos deportivos en vivo necesitan una plataforma que garantice la entrega de contenido sin interrupciones a millones de usuarios simultáneamente.

**Solución:** Construí una plataforma de streaming para eventos deportivos mejorando la entrega y escalabilidad a 10M usuarios simultáneos usando Java, AWS EKS, y Kafka.

**Impacto:** Soportó streaming para 10M usuarios simultáneos, redujo el tiempo de buffering a 200ms.

**Tecnologías:** Java, Springboot, AWS EKS, Kafka, OpenTelemetry

**Tecnologías nuevas a aprender:** Java, AWS EKS, Kafka, OpenTelemetry


## Plan por fases

1. Fase 1: Diseño de la infraestructura de streaming y arquitectura de microservicios
2. Fase 2: Desarrollo e implementación del backend usando Springboot
3. Fase 3: Configuración de Kafka para la gestión de eventos y telemetría
4. Fase 4: Despliegue en AWS EKS y pruebas de carga, monitoreo con OpenTelemetry

## Explicación

Este proyecto consiste en desarrollar una plataforma de streaming para eventos deportivos en vivo, capaz de manejar hasta 10 millones de usuarios simultáneamente. La solución se centra en el uso de tecnologías como Java con Spring Boot para el backend, AWS EKS para la orquestación de contenedores, y Apache Kafka para la transmisión de datos en tiempo real. Además, se utiliza OpenTelemetry para la observabilidad del sistema. A través de este proyecto, aprenderás cómo integrar y escalar servicios en la nube, manejar flujos de datos en tiempo real, y asegurar una baja latencia para mejorar la experiencia del usuario.

El sistema se basa en una arquitectura de microservicios desplegada en un clúster de Kubernetes (EKS) en AWS. Kafka se utiliza para la transmisión de eventos en tiempo real, asegurando que los datos se procesen y entreguen con baja latencia. OpenTelemetry se emplea para monitorear el rendimiento del sistema, permitiendo identificar y resolver problemas rápidamente.

Este proyecto te enseñará conceptos clave como la orquestación de contenedores con Kubernetes, la configuración y uso de Kafka para mensajería en tiempo real, y la implementación de prácticas de observabilidad con OpenTelemetry. También ganarás experiencia práctica en la configuración de servicios en AWS, especialmente en EKS, mejorando tus habilidades en la gestión de infraestructura en la nube.

## Arquitectura

```plaintext
  [Usuarios] 
      |
      v
[ELB (Elastic Load Balancer)]
      |
      v
  [AWS API Gateway]
      |
      v
  [AWS EKS (Kubernetes)]
      |
      v
[Microservicios Java (Spring Boot)]
      |
      v
[Kafka Cluster]
      |
      v
[OpenTelemetry]
```
- **Usuarios**: Clientes que se conectan para ver el streaming de eventos.
- **ELB**: Distribuye el tráfico de red de manera eficiente entre los servicios en la nube.
- **API Gateway**: Actúa como punto de entrada para todas las solicitudes, gestionando la autenticación y la autorización.
- **AWS EKS**: Orquesta los contenedores que ejecutan microservicios de Java.
- **Microservicios Java**: Procesan las solicitudes y gestionan la lógica de negocio.
- **Kafka Cluster**: Maneja la transmisión de eventos en tiempo real.
- **OpenTelemetry**: Colecta y proporciona datos de observabilidad del sistema.

## Entorno local (simulación cloud)

### Emulador Local

Para simular el entorno de AWS en local, usaremos LocalStack. Esto permitirá levantar servicios como EKS y Kafka de forma que puedas probar la infraestructura sin incurrir en costes reales.

1. **Servicios emulados**: EKS, Kafka
2. **Docker Compose**:
   ```bash
docker-compose -f docker-compose.yml up
   ```
3. **Desplegar IaC en el emulador**:
   ```bash
cd infra
tflocal apply
   ```
4. **Configurar aplicación para endpoints locales**:
   - Ajusta las variables de entorno para apuntar a LocalStack, por ejemplo: `AWS_ENDPOINT_URL=http://localhost:4566`
5. **Verificación**:
   - Lista los servicios activos con: `awslocal eks list-clusters`
   - Verifica Kafka con: `awslocal kafka list-clusters`

## Despliegue a cloud

### Despliegue en AWS

El despliegue en AWS se realizará utilizando la misma infraestructura como código probada en el entorno local.

1. **Cambios necesarios**:
   - Configura las credenciales de AWS en tu entorno.
   - Modifica el `provider` en `main.tf` para usar el endpoint real de AWS.

2. **Pasos de despliegue**:
   ```bash
cd infra
terraform apply
   ```

3. **Checklist de puesta en producción**:
   - Verifica que el clúster EKS esté activo.
   - Asegúrate de que Kafka esté correctamente configurado y funcionando.
   - Realiza pruebas de carga y monitoreo antes de redirigir tráfico real.

## Pruebas de carga

### Pruebas de Carga

1. **Herramienta**: k6
2. **Escenario de carga**:
   - Ramp-up a 10,000 usuarios concurrentes
   - Duración: 10 minutos
3. **Ejecución**:
   ```bash
k6 run loadtest/k6.js
   ```
4. **Observación de resultados**:
   - Integra Prometheus y Grafana para visualizar métricas de rendimiento.
   - Configura OpenTelemetry para rastrear el rendimiento durante las pruebas.

## Métricas objetivo

| Métrica | Objetivo | Cómo medir |
| --- | --- | --- |
| QPS / RPS objetivo | 10,000 req/s sostenidos | Se mide utilizando k6 y Grafana para observar las métricas de rendimiento. |
| Latencia p50 / p95 / p99 | p99 < 200ms | Monitoreado con OpenTelemetry y visualizado en Grafana. |
| Tasa de error | < 0.1% | Registros de errores de la aplicación y métricas de k6. |
| Punto de saturación | Degradación a partir de ~15,000 req/s | Observar el rendimiento y latencia a través de Prometheus y alertas configuradas. |

## Pruebas (unit / integración)

### Pruebas

Para asegurar la calidad del código, implementaremos pruebas unitarias y de integración.

#### Ejemplo de prueba unitaria en Java
```java
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class StreamingServiceTest {
    @Test
    void testStartStreaming() {
        StreamingService service = new StreamingService();
        assertDoesNotThrow(() -> service.startStreaming());
    }
}
```

#### Pruebas de integración
- Configura pruebas que interactúen con el entorno de Kafka simulado, verificando que los eventos se procesan correctamente.

## Complejidades técnicas

### Baja latencia en transmisión en tiempo real

**Por qué es difícil:** Es crucial mantener la latencia baja para asegurar una experiencia de usuario fluida, especialmente con millones de usuarios concurrentes.

**Cómo se resuelve:** Optimizar la configuración de Kafka para baja latencia, utilizando técnicas como compresión de mensajes y ajustes de particiones.

### Escalabilidad horizontal

**Por qué es difícil:** Manejar 10 millones de usuarios concurrentes requiere un sistema que pueda escalar eficientemente.

**Cómo se resuelve:** Implementar autoescalado en AWS EKS y optimizar el uso de recursos a través de configuraciones de Kubernetes.

### Observabilidad del sistema

**Por qué es difícil:** Con tantos componentes distribuidos, es fácil perder visibilidad del rendimiento del sistema.

**Cómo se resuelve:** Integrar OpenTelemetry para recolectar métricas y trazas, permitiendo un monitoreo continuo del sistema.


## Takeaways

- Desarrolle una plataforma de streaming escalable usando AWS EKS y Kafka.
- Implementé técnicas de baja latencia para mejorar la experiencia del usuario.
- Utilicé OpenTelemetry para monitorear y optimizar el rendimiento del sistema.
- Aprendí a desplegar y gestionar aplicaciones en un entorno de Kubernetes (EKS).
- Me familiaricé con la integración de Kafka para transmisión de eventos en tiempo real.

## Ruta de aprendizaje

### Java

Java es un lenguaje robusto y ampliamente utilizado en la industria, especialmente en sistemas de backend.

### Ruta de Aprendizaje Java
1. **Fundamentos de Java**
   - Aprende la sintaxis básica y conceptos orientados a objetos.
2. **Spring Boot**
   - Comprende cómo construir aplicaciones web con Spring Boot.
3. **Integración con Kafka**
   - Aprende a producir y consumir mensajes con Apache Kafka.
4. **Despliegue en AWS EKS**
   - Aprende a empaquetar y desplegar aplicaciones Java en Kubernetes.
5. **Monitoreo con OpenTelemetry**
   - Implementa prácticas de observabilidad en tus aplicaciones Java.

### Recursos
- [Java Programming Basics](https://www.oracle.com/java/technologies/javase-jdk11-doc-downloads.html)
- [Spring Boot Documentation](https://spring.io/projects/spring-boot)
- [Kafka Documentation](https://kafka.apache.org/documentation/)
- [AWS EKS Workshop](https://eksworkshop.com/)
- [OpenTelemetry Java](https://opentelemetry.io/docs/java/)

### AWS EKS

EKS permite gestionar aplicaciones en contenedores a gran escala, lo cual es crucial para sistemas distribuidos.

### Ruta de Aprendizaje AWS EKS
1. **Fundamentos de Kubernetes**
   - Aprende los conceptos básicos de Kubernetes.
2. **Configuración de EKS**
   - Aprende a configurar y gestionar un clúster EKS.
3. **Despliegue de Aplicaciones**
   - Aprende a desplegar aplicaciones en un clúster EKS.
4. **Autoescalado y Gestión de Recursos**
   - Implementa autoescalado y optimización de recursos.
5. **Monitoreo y Logging**
   - Implementa prácticas de monitoreo y logging en EKS.

### Recursos
- [Kubernetes Basics](https://kubernetes.io/docs/tutorials/kubernetes-basics/)
- [AWS EKS Documentation](https://docs.aws.amazon.com/eks/latest/userguide/what-is-eks.html)
- [EKS Best Practices](https://aws.github.io/aws-eks-best-practices/)
