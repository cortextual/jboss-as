/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.test.integration.domain.suites;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Collections;
import java.util.List;

import junit.framework.Assert;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.jboss.as.controller.client.Operation;
import org.jboss.as.controller.client.OperationBuilder;
import org.jboss.as.controller.client.helpers.domain.DomainClient;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.as.test.integration.domain.DomainTestSupport;
import org.jboss.as.test.shared.FileUtils;
import org.jboss.dmr.ModelNode;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.exporter.ExplodedExporter;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ADD;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.COMPOSITE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.CONTENT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.DEPLOYMENT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ENABLED;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.FULL_REPLACE_DEPLOYMENT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.HOST;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.INCLUDE_RUNTIME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.INPUT_STREAM_INDEX;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.NAME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.READ_RESOURCE_OPERATION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.REMOVE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.REPLACE_DEPLOYMENT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RUNTIME_NAME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SERVER;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SERVER_GROUP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SOCKET_BINDING;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SOCKET_BINDING_GROUP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.STEPS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.TO_REPLACE;
import static org.jboss.as.test.integration.domain.DomainTestSupport.cleanFile;
import static org.jboss.as.test.integration.domain.DomainTestSupport.validateResponse;
import static org.junit.Assert.assertEquals;

/**
 * Test of various management operations involving deployment overlays
 */
public class DeploymentOverlayTestCase {

    public static final String TEST_OVERLAY = "test";
    public static final String TEST_SERVER = "test-server";

    private static final String TEST = "test.war";
    private static final String REPLACEMENT = "test.war.v2";
    private static final ModelNode ROOT_ADDRESS = new ModelNode();
    private static final ModelNode ROOT_DEPLOYMENT_ADDRESS = new ModelNode();
    private static final ModelNode ROOT_REPLACEMENT_ADDRESS = new ModelNode();
    private static final ModelNode MAIN_SERVER_GROUP_ADDRESS = new ModelNode();
    private static final ModelNode MAIN_SERVER_GROUP_DEPLOYMENT_ADDRESS = new ModelNode();
    private static final ModelNode MAIN_RUNNING_SERVER_ADDRESS = new ModelNode();
    private static final ModelNode MAIN_RUNNING_SERVER_DEPLOYMENT_ADDRESS = new ModelNode();
    static {
        ROOT_ADDRESS.setEmptyList();
        ROOT_ADDRESS.protect();
        ROOT_DEPLOYMENT_ADDRESS.add(DEPLOYMENT, TEST);
        ROOT_DEPLOYMENT_ADDRESS.protect();
        ROOT_REPLACEMENT_ADDRESS.add(DEPLOYMENT, REPLACEMENT);
        ROOT_REPLACEMENT_ADDRESS.protect();
        MAIN_SERVER_GROUP_ADDRESS.add(SERVER_GROUP, "main-server-group");
        MAIN_SERVER_GROUP_ADDRESS.protect();
        MAIN_SERVER_GROUP_DEPLOYMENT_ADDRESS.add(SERVER_GROUP, "main-server-group");
        MAIN_SERVER_GROUP_DEPLOYMENT_ADDRESS.add(DEPLOYMENT, TEST);
        MAIN_SERVER_GROUP_DEPLOYMENT_ADDRESS.protect();
        MAIN_RUNNING_SERVER_ADDRESS.add(HOST, "master");
        MAIN_RUNNING_SERVER_ADDRESS.add(SERVER, "main-one");
        MAIN_RUNNING_SERVER_ADDRESS.protect();
        MAIN_RUNNING_SERVER_DEPLOYMENT_ADDRESS.add(HOST, "master");
        MAIN_RUNNING_SERVER_DEPLOYMENT_ADDRESS.add(SERVER, "main-one");
        MAIN_RUNNING_SERVER_DEPLOYMENT_ADDRESS.add(DEPLOYMENT, TEST);
        MAIN_RUNNING_SERVER_DEPLOYMENT_ADDRESS.protect();

    }

    private static DomainTestSupport testSupport;
    private static WebArchive webArchive;
    private static File tmpDir;

    @BeforeClass
    public static void setupDomain() throws Exception {

        // Create our deployment
        webArchive = ShrinkWrap.create(WebArchive.class, TEST);
        webArchive.addAsWebInfResource("deploymentoverlay/web.xml", "web.xml");
        webArchive.addClass(DeploymentOverlayServlet.class);


        // Make versions on the filesystem for URL-based deploy and for unmanaged content testing
        tmpDir = new File("target/deployments/" + DeploymentOverlayTestCase.class.getSimpleName());
        new File(tmpDir, "archives").mkdirs();
        new File(tmpDir, "exploded").mkdirs();
        webArchive.as(ZipExporter.class).exportTo(new File(tmpDir, "archives/" + TEST), true);
        webArchive.as(ExplodedExporter.class).exportExploded(new File(tmpDir, "exploded"));

        // Launch the domain
        testSupport = DomainTestSuite.createSupport(DeploymentOverlayTestCase.class.getSimpleName());


    }

    @AfterClass
    public static void tearDownDomain() throws Exception {
        try {
            testSupport = null;
            DomainTestSuite.stopSupport();
        } finally {
            cleanFile(tmpDir);
        }
    }

    /**
     * Validate that there are no deployments; try and clean if there are.
     *
     * @throws Exception
     */
    @Before
    @After
    public void confirmNoDeployments() throws Exception {
        List<ModelNode> deploymentList = getDeploymentList(ROOT_ADDRESS);
        if (deploymentList.size() > 0) {
            cleanDeployments();
        }
        deploymentList = getDeploymentList(new ModelNode());
        assertEquals("Deployments are removed from the domain", 0, deploymentList.size());
    }

    /**
     * Remove all deployments from the model.
     *
     * @throws java.io.IOException
     */
    private void cleanDeployments() throws IOException {
        List<ModelNode> deploymentList = getDeploymentList(MAIN_SERVER_GROUP_ADDRESS);
        for (ModelNode deployment : deploymentList) {
            removeDeployment(deployment.asString(), MAIN_SERVER_GROUP_ADDRESS);
        }
        deploymentList = getDeploymentList(ROOT_ADDRESS);
        for (ModelNode deployment : deploymentList) {
            removeDeployment(deployment.asString(), ROOT_ADDRESS);
        }
    }

    public void setupDeploymentOverride() throws Exception {

        ModelNode op = new ModelNode();
        op.get(ModelDescriptionConstants.OP_ADDR).set(ModelDescriptionConstants.DEPLOYMENT_OVERLAY, TEST_OVERLAY);
        op.get(ModelDescriptionConstants.OP).set(ModelDescriptionConstants.ADD);
        executeOnMaster(op);


        //add an override that will not be linked via a wildcard
        op = new ModelNode();
        op.get(ModelDescriptionConstants.OP_ADDR).set(new ModelNode());
        op.get(ModelDescriptionConstants.OP).set(ModelDescriptionConstants.UPLOAD_DEPLOYMENT_BYTES);
        op.get(ModelDescriptionConstants.BYTES).set(FileUtils.readFile(getClass().getClassLoader().getResource("deploymentoverlay/override.xml")).getBytes());
        ModelNode result = executeOnMaster(op);

        //add the content
        op = new ModelNode();
        ModelNode addr = new ModelNode();
        addr.add(ModelDescriptionConstants.DEPLOYMENT_OVERLAY, TEST_OVERLAY);
        addr.add(ModelDescriptionConstants.CONTENT, "WEB-INF/web.xml");
        op.get(ModelDescriptionConstants.OP_ADDR).set(addr);
        op.get(ModelDescriptionConstants.OP).set(ModelDescriptionConstants.ADD);
        op.get(ModelDescriptionConstants.CONTENT).set(result);
        executeOnMaster(op);

        //add the non-wildcard link to the server group
        addr = new ModelNode();
        addr.add(ModelDescriptionConstants.SERVER_GROUP, "main-server-group");
        addr.add(ModelDescriptionConstants.DEPLOYMENT_OVERLAY_LINK, TEST_OVERLAY);
        op = new ModelNode();
        op.get(ModelDescriptionConstants.OP_ADDR).set(addr);
        op.get(ModelDescriptionConstants.OP).set(ModelDescriptionConstants.ADD);
        op.get(ModelDescriptionConstants.DEPLOYMENT).set("test.war");
        op.get(ModelDescriptionConstants.DEPLOYMENT_OVERLAY).set(TEST_OVERLAY);
        executeOnMaster(op);

        //add the deployment overlay that will be linked to a single server
        op = new ModelNode();
        op.get(ModelDescriptionConstants.OP_ADDR).set(ModelDescriptionConstants.DEPLOYMENT_OVERLAY, TEST_SERVER);
        op.get(ModelDescriptionConstants.OP).set(ModelDescriptionConstants.ADD);
        executeOnMaster(op);

        op = new ModelNode();
        op.get(ModelDescriptionConstants.OP_ADDR).set(new ModelNode());
        op.get(ModelDescriptionConstants.OP).set(ModelDescriptionConstants.UPLOAD_DEPLOYMENT_BYTES);
        op.get(ModelDescriptionConstants.BYTES).set(FileUtils.readFile(getClass().getClassLoader().getResource( "deploymentoverlay/wildcard-override.xml")).getBytes());
        result = executeOnMaster(op);

        op = new ModelNode();
        addr = new ModelNode();
        addr.add(ModelDescriptionConstants.DEPLOYMENT_OVERLAY, TEST_SERVER);
        addr.add(ModelDescriptionConstants.CONTENT, "WEB-INF/web.xml");
        op.get(ModelDescriptionConstants.OP_ADDR).set(addr);
        op.get(ModelDescriptionConstants.OP).set(ModelDescriptionConstants.ADD);
        op.get(ModelDescriptionConstants.CONTENT).set(result);
        executeOnMaster(op);

        op = new ModelNode();
        op.get(ModelDescriptionConstants.OP_ADDR).set(new ModelNode());
        op.get(ModelDescriptionConstants.OP).set(ModelDescriptionConstants.UPLOAD_DEPLOYMENT_BYTES);
        op.get(ModelDescriptionConstants.BYTES).set("new file".getBytes());
        executeOnMaster(op);

        op = new ModelNode();
        addr = new ModelNode();
        addr.add(ModelDescriptionConstants.DEPLOYMENT_OVERLAY, TEST_SERVER);
        addr.add(ModelDescriptionConstants.CONTENT, "WEB-INF/classes/wildcard-new-file");
        op.get(ModelDescriptionConstants.OP_ADDR).set(addr);
        op.get(ModelDescriptionConstants.OP).set(ModelDescriptionConstants.ADD);
        op.get(ModelDescriptionConstants.CONTENT).set(result);
        executeOnMaster(op);
/*
        //add the per server link
        addr = new ModelNode();
        addr.add(ModelDescriptionConstants.HOST, "master");
        addr.add(ModelDescriptionConstants.SERVER, "main-one" );
        addr.add(ModelDescriptionConstants.DEPLOYMENT_OVERLAY_LINK, TEST_SERVER);
        op = new ModelNode();
        op.get(ModelDescriptionConstants.OP_ADDR).set(addr);
        op.get(ModelDescriptionConstants.OP).set(ModelDescriptionConstants.ADD);
        op.get(ModelDescriptionConstants.DEPLOYMENT).set("*.war");
        op.get(ModelDescriptionConstants.DEPLOYMENT_OVERLAY).set(TEST_SERVER);
        executeOnMaster(op);*/
    }


    /**
     * This test creates and links two deployment overlays, does a deployment, and then tests that the overlay has taken effect
     * @throws Exception
     */
    @Test
    public void testDeploymentOverlayInDomainMode() throws Exception {

        setupDeploymentOverride();

        ModelNode content = new ModelNode();
        content.get(INPUT_STREAM_INDEX).set(0);
        ModelNode composite = createDeploymentOperation(content, MAIN_SERVER_GROUP_DEPLOYMENT_ADDRESS);
        OperationBuilder builder = new OperationBuilder(composite, true);
        builder.addInputStream(webArchive.as(ZipExporter.class).exportAsInputStream());
        executeOnMaster(builder.build());

        DomainClient client = testSupport.getDomainMasterLifecycleUtil().createDomainClient();
        Assert.assertEquals("OVERRIDDEN", performHttpCall(client, "master", "main-one", "standard-sockets"));
        Assert.assertEquals("OVERRIDDEN", performHttpCall(client, "slave", "main-three", "standard-sockets"));

    }

    private String performHttpCall(DomainClient client, String host, String server, String socketBindingGroup) throws Exception {
        ModelNode op = new ModelNode();
        op.get(OP).set(READ_RESOURCE_OPERATION);
        op.get(OP_ADDR).add(HOST, host).add(SERVER, server).add(SOCKET_BINDING_GROUP, socketBindingGroup).add(SOCKET_BINDING, "http");
        op.get(INCLUDE_RUNTIME).set(true);
        ModelNode socketBinding = validateResponse(client.execute(op));

        URL url = new URL("http",
                org.jboss.as.arquillian.container.NetworkUtils.formatPossibleIpv6Address(socketBinding.get("bound-address").asString()),
                socketBinding.get("bound-port").asInt(),
                "/test/");
        HttpGet get = new HttpGet(url.toURI());
        HttpClient httpClient = new DefaultHttpClient();
        HttpResponse response = httpClient.execute(get);
        return getContent(response);
    }

    public static String getContent(HttpResponse response) throws IOException {
        InputStreamReader reader = new InputStreamReader(response.getEntity().getContent());
        StringBuilder content = new StringBuilder();
        int c;
        while (-1 != (c = reader.read())) {
            content.append((char) c);
        }
        reader.close();
        return content.toString();
    }

    private static ModelNode executeOnMaster(ModelNode op) throws IOException {
        return validateResponse(testSupport.getDomainMasterLifecycleUtil().getDomainClient().execute(op));
    }

    private static ModelNode executeOnMaster(Operation op) throws IOException {
        return validateResponse(testSupport.getDomainMasterLifecycleUtil().getDomainClient().execute(op));
    }

    private static ModelNode createDeploymentOperation(ModelNode content, ModelNode... serverGroupAddressses) {
        ModelNode composite = getEmptyOperation(COMPOSITE, ROOT_ADDRESS);
        ModelNode steps = composite.get(STEPS);
        ModelNode step1 = steps.add();
        step1.set(getEmptyOperation(ADD, ROOT_DEPLOYMENT_ADDRESS));
        step1.get(CONTENT).add(content);
        for (ModelNode serverGroup : serverGroupAddressses) {
            ModelNode sg = steps.add();
            sg.set(getEmptyOperation(ADD, serverGroup));
            sg.get(ENABLED).set(true);
        }

        return composite;
    }

    private static ModelNode createDeploymentReplaceOperation(ModelNode content, ModelNode... serverGroupAddressses) {
        ModelNode composite = getEmptyOperation(COMPOSITE, ROOT_ADDRESS);
        ModelNode steps = composite.get(STEPS);
        ModelNode step1 = steps.add();
        step1.set(getEmptyOperation(ADD, ROOT_REPLACEMENT_ADDRESS));
        step1.get(RUNTIME_NAME).set(TEST);
        step1.get(CONTENT).add(content);
        for (ModelNode serverGroup : serverGroupAddressses) {
            ModelNode sgr = steps.add();
            sgr.set(getEmptyOperation(REPLACE_DEPLOYMENT, serverGroup));
            sgr.get(ENABLED).set(true);
            sgr.get(NAME).set(REPLACEMENT);
            sgr.get(TO_REPLACE).set(TEST);
        }

        return composite;
    }

    private static ModelNode createDeploymentFullReplaceOperation(ModelNode content) {
        ModelNode op = getEmptyOperation(FULL_REPLACE_DEPLOYMENT, ROOT_ADDRESS);
        op.get(NAME).set(TEST);
        op.get(CONTENT).add(content);
        return op;
    }

    private static List<ModelNode> getDeploymentList(ModelNode address) throws IOException {
        ModelNode op = getEmptyOperation("read-children-names", address);
        op.get("child-type").set("deployment");

        ModelNode response = testSupport.getDomainMasterLifecycleUtil().getDomainClient().execute(op);
        ModelNode result = validateResponse(response);
        return result.isDefined() ? result.asList() : Collections.<ModelNode>emptyList();
    }

    private static void removeDeployment(String deploymentName, ModelNode address) throws IOException {
        ModelNode deplAddr = new ModelNode();
        deplAddr.set(address);
        deplAddr.add("deployment", deploymentName);
        ModelNode op = getEmptyOperation(REMOVE, deplAddr);
        ModelNode response = testSupport.getDomainMasterLifecycleUtil().getDomainClient().execute(op);
        validateResponse(response);
    }

    private static ModelNode getEmptyOperation(String operationName, ModelNode address) {
        ModelNode op = new ModelNode();
        op.get(OP).set(operationName);
        if (address != null) {
            op.get(OP_ADDR).set(address);
        }
        else {
            // Just establish the standard structure; caller can fill in address later
            op.get(OP_ADDR);
        }
        return op;
    }

}
