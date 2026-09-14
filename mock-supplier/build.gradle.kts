plugins {
	java
	id("org.springframework.boot")
	id("io.spring.dependency-management")
}

description = "Mock supplier server for local integration"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
}
