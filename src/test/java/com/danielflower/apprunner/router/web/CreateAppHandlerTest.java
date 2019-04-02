package com.danielflower.apprunner.router.web;

import org.junit.Test;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.*;

public class CreateAppHandlerTest {

    @Test
    public void canGetAppName() {
        String rawBody = "gitUrl=file%3A%2FD%3A%2Fcode%2Fapp-runner-router%2Ftarget%2Fsamples%2F2bfa1222-b0ac-4e38-ab10-6b3d8815b499%2Fmaven%2F&appName=app%201";
        String name = CreateAppHandler.getNameFromBody(rawBody);
        assertThat(name, equalTo("app 1"));
    }

    @Test
    public void defaultsBasedOnGitUrl() {
        String rawBody = "gitUrl=file%3A%2FD%3A%2Fcode%2Fapp-runner-router%2Ftarget%2Fsamples%2F2bfa1222-b0ac-4e38-ab10-6b3d8815b499%2Fmaven%2F&appName=";
        String name = CreateAppHandler.getNameFromBody(rawBody);
        assertThat(name, equalTo("maven"));
    }

    @Test
    public void defaultsBasedOnGitUrl2() {
        String rawBody = "gitUrl=file%3A%2FD%3A%2Fcode%2Fapp-runner-router%2Ftarget%2Fsamples%2F2bfa1222-b0ac-4e38-ab10-6b3d8815b499%2Fmaven.git";
        String name = CreateAppHandler.getNameFromBody(rawBody);
        assertThat(name, equalTo("maven"));
    }
}