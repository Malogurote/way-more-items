package waymoreitems.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marque la classe d'extension d'un mod pour Way More Items.
 *
 * <p>La classe annotée doit implémenter {@link WmiExtension} et offrir un constructeur public sans argument.
 * WMI la découvre par l'analyse des annotations du chargeur de mods, sans qu'aucune classe de WMI ne soit
 * nécessaire à l'exécution : si WMI est absent, la classe n'est jamais chargée. Le mod déclare donc WMI comme
 * dépendance facultative et compile contre le jar de l'API seul ({@code compileOnly}), sans jamais l'embarquer.</p>
 *
 * <pre>{@code
 * @WmiPlugin(api = WmiApi.VERSION)
 * public final class ExampleWmiExtension implements WmiExtension { ... }
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface WmiPlugin {
    /** Version de l'API contre laquelle l'extension a été compilée : toujours {@link WmiApi#VERSION}. */
    String api();
}
