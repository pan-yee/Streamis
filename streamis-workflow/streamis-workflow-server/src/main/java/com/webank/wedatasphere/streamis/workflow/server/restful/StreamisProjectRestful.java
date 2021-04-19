package com.webank.wedatasphere.streamis.workflow.server.restful;


import com.fasterxml.jackson.databind.JsonNode;
import com.webank.wedatasphere.linkis.server.security.SecurityFilter;
import com.webank.wedatasphere.streamis.workflow.server.service.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;


/**
 * this is the restful class for streamis project
 */

@Component
@Path("/streamis")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class StreamisProjectRestful {



    private static final Logger LOGGER = LoggerFactory.getLogger(StreamisProjectRestful.class);


    @Autowired
    private ProjectService projectService;




    @POST
    @Path("createProject")
    public Response createProject(@Context HttpServletRequest request, JsonNode jsonNode){
        String username = SecurityFilter.getLoginUsername(request);
        String projectName = jsonNode.get("projectName").asText();
        String description = jsonNode.get("description").asText();

        return null;
    }








}
