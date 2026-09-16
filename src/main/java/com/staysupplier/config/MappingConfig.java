package com.staysupplier.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.staysupplier.mapping.MappingSyncProperties;

/**
 * 매핑 갱신 잡 설정 바인딩.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MappingSyncProperties.class)
public class MappingConfig {

}
