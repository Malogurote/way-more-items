/**
 * API publique de Way More Items (jar {@code wmi-api}, licence MIT) : interfaces et types simples, sans
 * implémentation ni dépendance envers Minecraft.
 *
 * <p>Trois niveaux, du plus simple au plus complet :</p>
 * <ol>
 *   <li>rien à faire : WMI lit l'affichage des recettes de Minecraft ({@code Recipe#display()}) ;</li>
 *   <li>une mise en page sans Java : {@code assets/<modid>/wmi_layouts/<type>.json}, format
 *       {@code wmi_recipe_layout} (cases, flèches, jauges, flamme) ;</li>
 *   <li>une extension Java : une classe annotée {@link waymoreitems.api.WmiPlugin} qui implémente
 *       {@link waymoreitems.api.WmiExtension} et reçoit un {@link waymoreitems.api.WmiRegistration}.</li>
 * </ol>
 *
 * <pre>{@code
 * @WmiPlugin(api = WmiApi.VERSION)
 * public final class ExampleWmiExtension implements WmiExtension {
 *     public String id() { return "examplemod"; }
 *
 *     public void register(WmiRegistration reg) {
 *         reg.addRecipes(() -> List.of(WmiRecipe.builder("examplemod:grinding/iron", "examplemod:grinding")
 *                 .input(WmiIngredient.item("minecraft:raw_iron", 1))
 *                 .input(WmiIngredient.fluid("minecraft:water", 250))
 *                 .output("examplemod:iron_dust", 2)
 *                 .duration(200).energy(8)
 *                 .build()));
 *         reg.addInfo("examplemod:grinder", List.of("examplemod.info.grinder"));
 *         reg.excludeFromCraftTree("examplemod:recycling");
 *     }
 * }
 * }</pre>
 *
 * <p>Le mod compile contre ce jar en {@code compileOnly}, ne l'embarque jamais et déclare WMI comme dépendance
 * facultative. Versions strictes : voir {@link waymoreitems.api.WmiApi}.</p>
 */
package waymoreitems.api;
