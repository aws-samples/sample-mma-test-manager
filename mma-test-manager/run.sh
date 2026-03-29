#!/bin/bash
mvn clean compile package

mvn spring-boot:run -Dspring-boot.run.arguments=--spring.config.location=application-secretsmanager.properties
