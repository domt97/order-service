package com.dotran.oms.order.infrastructure.config;

import com.dotran.oms.order.application.config.CheckoutProperties;
import com.dotran.oms.order.application.config.StockSagaProperties;
import com.dotran.oms.order.infrastructure.client.StoresClientProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({CheckoutProperties.class, StockSagaProperties.class, StoresClientProperties.class})
public class PropertiesConfig {
}
