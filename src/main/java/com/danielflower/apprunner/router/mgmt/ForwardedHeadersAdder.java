package com.danielflower.apprunner.router.mgmt;

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.proxy.AbstractProxyServlet;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class ForwardedHeadersAdder extends AbstractProxyServlet {

    @Override
    public String getViaHost() {
        return "arr";
    }

    public void addHeaders(HttpServletRequest clientRequest, Request targetRequest) {
        if (clientRequest != null) {
            targetRequest.header(HttpHeader.HOST, clientRequest.getHeader("HOST"));
            addProxyHeaders(clientRequest, targetRequest);
        }
    }

    @Override
    protected Response.CompleteListener newProxyResponseListener(HttpServletRequest clientRequest, HttpServletResponse proxyResponse) {
        throw new UnsupportedOperationException("This is just used for adding proxy headers, not stuff like this");
    }
}
