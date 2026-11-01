package io.connglli.gorrect.engine.gir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AutomaticQueueAdmissionTest {
  private static final String PREFIX =
      "gir 1; shape W weak; queue q W; root permanent = null; root other = null; program {";

  @Test
  void liveConstructorHandleProtectsDelayedPublicationAcrossCollection() {
    var program =
        GirParser.parse(
            PREFIX
                + "allocate-ref target W null; allocate-ref r W target q; collect;"
                + " write-root permanent r; release r; release target; }");
    assertTrue(ReferenceProgram.usesAutomaticQueues(program));
    assertTrue(ReferenceProgram.firstClassGaps(program).isEmpty());
  }

  @Test
  void priorClosedScopeNamesDoNotAffectTheConstructorBindingLifetime() {
    for (String prefix :
        new String[] {
          "scope { allocate-ref r W null; release r; }",
          "scope { allocate-ref r W null; }",
          "repeat 0 { allocate-ref r W null; release r; }"
        }) {
      var program =
          GirParser.parse(
              PREFIX
                  + prefix
                  + " allocate-ref target W null; allocate-ref r W target q; write-root permanent"
                  + " r; }");
      assertTrue(ReferenceProgram.firstClassGaps(program).isEmpty());
    }
  }

  @Test
  void additionalRootsAndAliasesRetainTheirContracts() {
    var program =
        GirParser.parse(
            PREFIX
                + "allocate-ref target W null; allocate-ref r W target q;"
                + " write-root permanent r; write-root other r; write-root other null;"
                + " read-root alias W permanent; write-root other alias; }");
    assertTrue(ReferenceProgram.firstClassGaps(program).isEmpty());
  }

  @Test
  void nonliteralNullStillNeedsTheSourceLifetimeProof() {
    assertThrows(
        GirException.class,
        () -> GirParser.parse(PREFIX + "poll-queue absent q; allocate-ref r W absent q; }"));
    var program =
        GirParser.parse(
            PREFIX + "poll-queue absent q; allocate-ref r W absent q; write-root permanent r; }");
    assertTrue(ReferenceProgram.usesAutomaticQueues(program));
    assertFalse(
        ReferenceProgram.usesAutomaticQueues(
            GirParser.parse(PREFIX + "repeat 2 { allocate-ref r W null q; release r; } }")));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "allocate-ref target W null; allocate-ref r W target q;",
        "allocate-ref target W null; repeat 0 { allocate-ref r W target q; write-root permanent r;"
            + " }",
        "allocate-ref target W null; scope { allocate-ref r W target q; write-root permanent r; }",
        "allocate-ref target W null; allocate-ref r W target q; scope { write-root permanent r; }",
        "allocate-ref target W null; allocate-ref r W target q; release r; write-root permanent"
            + " null;",
        "allocate-ref target W null; allocate-ref r W target q; write-root permanent r; write-root"
            + " permanent r;",
        "allocate-ref target W null; allocate-ref r W target q; write-root permanent r; repeat 0 {"
            + " write-root permanent null; }",
        "allocate-ref target W null; write-root permanent target; allocate-ref r W target q;"
            + " write-root permanent r;",
        "allocate-ref target W null; allocate-ref r W target q; write-root permanent r;"
            + " compare-set-root changed permanent r null;",
        "allocate-ref target W null; allocate-ref r W target q; write-root permanent r;"
            + " exchange-root previous W permanent null;"
      })
  void wholeSyntaxRejectsUnprovedLifetimeAndEveryOtherRootWrite(String body) {
    assertThrows(GirException.class, () -> GirParser.parse(PREFIX + body + "}"));
  }

  @Test
  void rootMustBeInitiallyNullAndPublicationMustUseTheConstructorBinding() {
    assertThrows(
        GirException.class,
        () ->
            GirParser.parse(
                "gir 1; shape N object 0 0; shape W weak; object 1 N [] [];"
                    + " queue q W; root permanent = 1; program { allocate-ref r W null;"
                    + " allocate-ref s W r q; write-root permanent s; }"));
    assertThrows(
        GirException.class,
        () ->
            GirParser.parse(
                PREFIX
                    + "allocate-ref target W null; allocate-ref r W target q;"
                    + " write-root other r; read-root alias W other; write-root permanent alias;"
                    + " write-root other null; }"));
  }
}
