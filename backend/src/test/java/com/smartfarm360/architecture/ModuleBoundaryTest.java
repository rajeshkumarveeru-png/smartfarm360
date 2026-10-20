package com.smartfarm360.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Module boundaries from ADR 0001. Allowed directions: everything → platform; crop, finance → costing;
 * inventory → finance; operations (field work that moves stock and money) → crop, inventory, finance. Finance never
 * depends on the farming or stock modules, crop never on finance or stock, and nothing depends on operations.
 */
@AnalyzeClasses(packages = "com.smartfarm360", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryTest {
    private static final String[] BUSINESS = {"com.smartfarm360.farm..", "com.smartfarm360.crop..", "com.smartfarm360.finance..",
            "com.smartfarm360.inventory..", "com.smartfarm360.costing..", "com.smartfarm360.operations.."};

    @ArchTest
    static final ArchRule platformDoesNotDependOnBusinessModules = noClasses().that().resideInAPackage("com.smartfarm360.platform..")
            .should().dependOnClassesThat().resideInAnyPackage(BUSINESS);

    @ArchTest
    static final ArchRule costingIsALeafModule = noClasses().that().resideInAPackage("com.smartfarm360.costing..")
            .should().dependOnClassesThat().resideInAnyPackage("com.smartfarm360.farm..", "com.smartfarm360.crop..",
                    "com.smartfarm360.finance..", "com.smartfarm360.inventory..");

    @ArchTest
    static final ArchRule farmDoesNotDependOnOtherModules = noClasses().that().resideInAPackage("com.smartfarm360.farm..")
            .should().dependOnClassesThat().resideInAnyPackage("com.smartfarm360.crop..", "com.smartfarm360.finance..", "com.smartfarm360.inventory..");

    @ArchTest
    static final ArchRule cropDoesNotDependOnFinanceOrStock = noClasses().that().resideInAPackage("com.smartfarm360.crop..")
            .should().dependOnClassesThat().resideInAnyPackage("com.smartfarm360.finance..", "com.smartfarm360.inventory..");

    @ArchTest
    static final ArchRule financeDoesNotDependOnFarmingOrStock = noClasses().that().resideInAPackage("com.smartfarm360.finance..")
            .should().dependOnClassesThat().resideInAnyPackage("com.smartfarm360.crop..", "com.smartfarm360.inventory..", "com.smartfarm360.farm..");

    @ArchTest
    static final ArchRule nothingDependsOnOperations = noClasses().that().resideOutsideOfPackage("com.smartfarm360.operations..")
            .should().dependOnClassesThat().resideInAPackage("com.smartfarm360.operations..");

    @ArchTest
    static final ArchRule businessModulesDoNotBypassTenantContext = noClasses()
            .that().resideInAnyPackage(BUSINESS)
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.smartfarm360.platform.membership..", "com.smartfarm360.platform.auth..", "com.smartfarm360.platform.user..")
            .because("modules get tenant access and the current user from TenantContext/CurrentUser, not from membership or account tables");
}
