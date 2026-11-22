package com.subscriptionplatform.graphql;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;

@Configuration(proxyBeanMethods = false)
public class GraphQlConfiguration {

    @Bean
    public RuntimeWiringConfigurer customScalars() {
        return wiring -> wiring.scalar(Scalars.DATE).scalar(Scalars.DATE_TIME).scalar(Scalars.DECIMAL);
    }
}
