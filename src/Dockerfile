// Dockerfile para construir la imagen del microservicio Java.

FROM openjdk:11-jre-slim
WORKDIR /app
COPY target/streaming-service.jar ./
CMD ["java", "-jar", "streaming-service.jar"]