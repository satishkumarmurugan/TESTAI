package com.example.agentplatform.bootstrap;

import com.example.agentplatform.config.PlatformProperties;
import com.example.agentplatform.domain.*;
import com.example.agentplatform.repo.AgentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Seeds two demo agents (kawach, notification) pointing at the built-in /mock endpoints,
 * so the whole flow works without any external service. Disable with platform.seed-examples=false.
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private final AgentRepository repo;
    private final PlatformProperties props;
    private final int port;

    public DataSeeder(AgentRepository repo, PlatformProperties props, @Value("${server.port:8080}") int port) {
        this.repo = repo;
        this.props = props;
        this.port = port;
    }

    @Override
    public void run(String... args) {
        if (!props.isSeedExamples() || repo.count() > 0) return;
        repo.save(kawach());
        repo.save(notification());
    }

    private Agent kawach() {
        Agent a = agent("kawach",
                "Centralized UAM: manages users, groups and roles per application realm.",
                "http://localhost:" + port + "/mock/kawach");

        Tool createUser = tool("createUser", "Create a user in an application realm.", "POST",
                "/{realm}/create-user", true);
        createUser.addParam(param("realm", ParamLocation.PATH, true, "Application (realm) name"));
        createUser.addParam(param("username", ParamLocation.BODY, true, "Login name of the new user"));
        createUser.addParam(param("email", ParamLocation.BODY, false, "Email address of the user"));
        a.addTool(createUser);

        Tool addGroup = tool("addGroup", "Add a group to an application realm.", "POST",
                "/{realm}/addGroup", true);
        addGroup.addParam(param("realm", ParamLocation.PATH, true, "Application (realm) name"));
        addGroup.addParam(param("groupName", ParamLocation.BODY, true, "Name of the group"));
        a.addTool(addGroup);

        Tool addRole = tool("addRole", "Add a role to an application realm.", "POST",
                "/{realm}/addRole", true);
        addRole.addParam(param("realm", ParamLocation.PATH, true, "Application (realm) name"));
        addRole.addParam(param("roleName", ParamLocation.BODY, true, "Name of the role"));
        a.addTool(addRole);

        Tool listUsers = tool("listUsers", "List users that exist in an application realm.", "GET",
                "/{realm}/users", false);
        listUsers.addParam(param("realm", ParamLocation.PATH, true, "Application (realm) name"));
        a.addTool(listUsers);
        return a;
    }

    private Agent notification() {
        Agent a = agent("notification",
                "Sends email and SMS notifications and checks delivery status.",
                "http://localhost:" + port + "/mock/notify");

        Tool email = tool("sendEmail", "Send an email to one recipient.", "POST", "/email/send", true);
        email.addParam(param("to", ParamLocation.BODY, true, "Recipient email address"));
        email.addParam(param("subject", ParamLocation.BODY, true, "Email subject"));
        email.addParam(param("message", ParamLocation.BODY, true, "Email body text"));
        a.addTool(email);

        Tool sms = tool("sendSms", "Send an SMS to a mobile number.", "POST", "/sms/send", true);
        sms.addParam(param("mobile", ParamLocation.BODY, true, "10-digit mobile number"));
        sms.addParam(param("message", ParamLocation.BODY, true, "SMS text"));
        a.addTool(sms);

        Tool status = tool("getStatus", "Get the delivery status of a notification by its id.", "GET",
                "/status/{notificationId}", false);
        status.addParam(param("notificationId", ParamLocation.PATH, true,
                "Id returned when the notification was sent"));
        a.addTool(status);
        return a;
    }

    private Agent agent(String name, String description, String baseUrl) {
        Agent a = new Agent();
        a.setName(name);
        a.setDescription(description);
        a.setBaseUrl(baseUrl);
        a.setStatus(AgentStatus.ACTIVE);
        return a;
    }

    private Tool tool(String name, String description, String method, String path, boolean confirm) {
        Tool t = new Tool();
        t.setName(name);
        t.setDescription(description);
        t.setHttpMethod(method);
        t.setContextPath(path);
        t.setRequiresConfirmation(confirm);
        return t;
    }

    private ToolParam param(String name, ParamLocation loc, boolean required, String description) {
        ToolParam p = new ToolParam();
        p.setName(name);
        p.setType("string");
        p.setLocation(loc);
        p.setRequired(required);
        p.setDescription(description);
        return p;
    }
}
