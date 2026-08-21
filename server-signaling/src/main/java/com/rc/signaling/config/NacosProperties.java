package com.rc.signaling.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Nacos naming configuration used only for Relay discovery. */
@Component
@ConfigurationProperties(prefix = "rc.nacos")
public class NacosProperties {
    private boolean enabled;
    private String serverAddr = "127.0.0.1:8848";
    private String namespace = "public";
    private String username = "nacos";
    private String password = "nacos";
    private String serviceName = "javadesk-relay";
    private String groupName = "RELAY_GROUP";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getServerAddr() { return serverAddr; }
    public void setServerAddr(String serverAddr) { this.serverAddr = serverAddr; }
    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }
}
