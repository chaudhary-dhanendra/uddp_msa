package gov.uddp.shaping;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "gov.uddp", importOptions = ImportOption.DoNotIncludeTests.class)
class ShapingBoundaryTest {

    @ArchTest
    static final ArchRule coreUsesOnlyTheShapingApi = noClasses().that().resideInAPackage("gov.uddp.core..")
            .should().dependOnClassesThat(resideInAPackage("gov.uddp.shaping..")
                    .and(resideOutsideOfPackage("gov.uddp.shaping.api..")));

    @ArchTest
    static final ArchRule shapingStaysOutOfMatchingGoldenAndCanonical = noClasses().that()
            .resideInAPackage("gov.uddp.shaping..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "gov.uddp.core.matching..", "gov.uddp.core.golden..", "gov.uddp.core.canonical..");
}
