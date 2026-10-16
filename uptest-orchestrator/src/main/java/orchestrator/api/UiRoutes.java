package orchestrator.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the React bundle's entry page at /ui/.
 *
 * Without this the URL 404s, and the message is misleading enough to have cost
 * a debugging round:
 *
 *     No static resource ui for request '/ui/'
 *
 * It reads as "the files are missing" and they are not. Spring Framework 6's
 * ResourceHttpRequestHandler refuses to serve a DIRECTORY — it looked for a
 * file literally named "ui" under classpath:/static/, found a folder, and gave
 * up. Spring Boot's automatic index.html handling (WelcomePageHandlerMapping)
 * only ever covers the ROOT path, so classpath:/static/index.html is served at
 * "/" and classpath:/static/ui/index.html is served at NOTHING until someone
 * says so. That asymmetry is the whole bug: the old vanilla-JS UI sits at the
 * root and worked without any configuration, so nothing in this project had
 * ever needed a rule, and the React bundle inherited an assumption that was
 * only ever true for one directory.
 *
 * Both spellings are registered because a person types /ui and a link carries
 * /ui/, and a redirect between them would be one more hop to explain.
 *
 * DELIBERATELY NOT A CATCH-ALL. The tempting SPA rule — forward every unmatched
 * path under /ui/** to index.html — is wrong here on two counts. This app does
 * client-side navigation with useState (App.tsx holds a View in state; there is
 * no react-router and no URL to deep-link), so no such path exists to rescue.
 * And a catch-all would answer 200 + index.html for a MISSING asset, turning a
 * loud 404 on a stale bundle filename into a blank page with no explanation —
 * the same class of silent pass this codebase keeps paying for elsewhere. If
 * routing is ever added to the UI, add the catch-all then, scoped to paths that
 * are not files.
 */
@Configuration
class UiRoutes implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/ui").setViewName("forward:/ui/index.html");
        registry.addViewController("/ui/").setViewName("forward:/ui/index.html");
    }
}
