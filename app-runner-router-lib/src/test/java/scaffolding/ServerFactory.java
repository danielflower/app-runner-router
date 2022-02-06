package scaffolding;

import com.danielflower.apprunner.router.lib.App;
import com.danielflower.apprunner.router.lib.Config;
import io.muserver.MuServerBuilder;

import java.util.Map;

public class ServerFactory {

    public static App startedRouter(Map<String,String> env, MuServerBuilder serverBuilder) throws Exception {
        Config config = new Config(env);
        App router = new App(config);
        router.start(serverBuilder);
        return router;
    }
}
