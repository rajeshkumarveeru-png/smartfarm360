package com.smartfarm360.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Module boundaries from ADR 0001. */
@AnalyzeClasses(packages = "com.smartfarm360", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryTest {

    @ArchTest
    static final ArchRule platformDoesNotDependOnBusinessModules = noClasses().that().resideInAPackage("com.smartfarm360.platform..")
            .should().dependOnClassesThat().resideInAnyPackage("com.smartfarm360.farm..", "com.smartfarm360.crop..", "com.smartfarm360.finance..");

    @ArchTest
    static final ArchRule farmDoesNotDependOnCropOrFinance = noClasses().that().resideInAPackage("com.smartfarm360.farm..")
            .should().dependOnClassesThat().resideInAnyPackage("com.smartfarm360.crop..", "com.smartfarm360.finance..");

    @ArchTest
    static final ArchRule cropAndFinanceAreIndependent = noClasses().that().resideInAPackage("com.smartfarm360.crop..")
            .should().dependOnClassesThat().resideInAPackage("com.smartfarm360.finance..");

    @ArchTest
    static final ArchRule financeDoesNotDependOnCrop = noClasses().that().resideInAPackage("com.smartfarm360.finance..")
            .should().dependOnClassesThat().resideInAPackage("com.smartfarm360.crop..");

    @ArchTest
    static final ArchRule businessModulesDoNotBypassTenantContext = noClasses()
            .that().resideInAnyPackage("com.smartfarm360.farm..", "com.smartfarm360.crop..", "com.smartfarm360.finance..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.smartfarm360.platform.membership..", "com.smartfarm360.platform.auth..", "com.smartfarm360.platform.user..")
            .because("modules get tenant access and the current user from TenantContext/CurrentUser, not from membership or account tables");
}
