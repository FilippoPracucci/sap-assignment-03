FROM maven:3.9.9-eclipse-temurin-21 AS build  
WORKDIR /project
COPY . /project/
ENV TZ="Europe/Rome"
RUN mvn clean package -DskipTests
EXPOSE 8080