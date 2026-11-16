plugins {
  id("uk.gov.justice.hmpps.gradle-spring-boot") version "11.0.15"
  kotlin("plugin.spring") version "2.4.21"
  kotlin("plugin.jpa") version "2.4.21"
  id("se.patrikerdes.use-latest-versions") version "0.2.19"
}

tasks.withType<Test> {
  environment("AWS_REGION", "eu-west-1")
  useJUnitPlatform()
}

configurations {
  testImplementation { exclude(group = "org.junit.vintage") }
}

dependencies {
  annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

  implementation("org.springframework.boot:spring-boot-starter-flyway")
  runtimeOnly("org.flywaydb:flyway-database-postgresql")
  runtimeOnly("org.postgresql:postgresql:42.7.14")

  implementation("io.opentelemetry.instrumentation:opentelemetry-instrumentation-annotations:2.33.1")

  implementation("io.jsonwebtoken:jjwt-api:0.13.0")
  runtimeOnly("io.jsonwebtoken:jjwt-impl:0.13.0")
  runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.13.0")

  implementation("uk.gov.justice.service.hmpps:hmpps-kotlin-spring-boot-starter:3.1.0")
  implementation("org.springframework.boot:spring-boot-starter-security")
  implementation("org.springframework.boot:spring-boot-starter-webflux")
  implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
  implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
  implementation("org.springframework.boot:spring-boot-starter-data-jpa")
  implementation("org.springframework.boot:spring-boot-starter-actuator")
  implementation("org.springframework.boot:spring-boot-starter-cache")
  implementation("org.springframework.security:spring-security-config")
  // Temporarily pin swagger-ui for dompurify issues CVE-2026-11680(2-4)
  constraints {
    implementation("org.webjars:swagger-ui:5.34.1")
  }

  implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.2.0")

  implementation("net.javacrumbs.shedlock:shedlock-spring:7.11.0")
  implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:7.11.0")

  implementation("io.awspring.cloud:spring-cloud-aws-starter-s3:4.2.1")
  implementation("software.amazon.awssdk:sts")
  implementation("software.amazon.awssdk:netty-nio-client")

  implementation("uk.gov.justice.service.hmpps:hmpps-sqs-spring-boot-starter:7.4.1")

  testImplementation("org.springframework.boot:spring-boot-starter-test") {
    exclude(group = "junit")
  }
  testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
  testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
  testImplementation("org.springframework.boot:spring-boot-test-autoconfigure")
  testImplementation("org.springframework.boot:spring-boot-webflux-test")

  testImplementation("io.swagger.parser.v3:swagger-parser:2.1.48") {
    exclude(group = "io.swagger.core.v3")
    exclude(group = "io.swagger.parser.v3", module = "swagger-parser-v2-converter")
  }
  testImplementation("org.wiremock:wiremock-standalone:3.13.2")
  testImplementation("io.opentelemetry:opentelemetry-sdk-testing:1.68.0")
  testImplementation("org.testcontainers:postgresql:1.21.4")
  testImplementation("org.apache.commons:commons-csv:1.14.1")
}

kotlin {
  jvmToolchain(25)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
  compilerOptions.jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25
}
