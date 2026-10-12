package com.example.agentplatform.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "platform")
@Getter @Setter
public class PlatformProperties {
    private List<String> corsOrigins = new ArrayList<>(List.of("http://localhost:4200"));
    private List<String> allowedHosts = new ArrayList<>();
    private boolean seedExamples = true;
    private boolean mockServicesEnabled = true;
}
