package com.staysupplier.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.staysupplier.mapping.MappingReloadProperties;
import com.staysupplier.mapping.MappingSyncProperties;

/**
 * 매핑 갱신 잡·리로드 설정 바인딩.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ MappingSyncProperties.class, MappingReloadProperties.class })
public class MappingConfig {

}
