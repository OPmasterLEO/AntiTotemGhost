package net.opmasterleo.masterantighost.combat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.opmasterleo.masterantighost.combat.SwapEvidenceLedger.Evidence;

@DisplayName("SwapEvidenceLedger Tests")
class SwapEvidenceLedgerTest {

    @Test
    @DisplayName("Sandbox mode always allows pop attempt")
    void testSandboxAlwaysAllows() {
        SwapEvidenceLedger ledger = new SwapEvidenceLedger(null, null);
        Evidence evidence = new Evidence(false, false, false);
        assertTrue(ledger.shouldAttemptPop(evidence, true));
    }

    @Test
    @DisplayName("Immediate totem evidence allows pop attempt")
    void testImmediateTotemAllows() {
        SwapEvidenceLedger ledger = new SwapEvidenceLedger(null, null);
        Evidence evidence = new Evidence(true, false, false);
        assertTrue(ledger.shouldAttemptPop(evidence, false));
    }

    @Test
    @DisplayName("Recent totem activity allows pop attempt")
    void testRecentTotemActivityAllows() {
        SwapEvidenceLedger ledger = new SwapEvidenceLedger(null, null);
        Evidence evidence = new Evidence(false, true, true);
        assertTrue(ledger.shouldAttemptPop(evidence, false));
    }

    @Test
    @DisplayName("No evidence blocks pop attempt")
    void testNoEvidenceBlocks() {
        SwapEvidenceLedger ledger = new SwapEvidenceLedger(null, null);
        Evidence evidence = new Evidence(false, false, true);
        assertFalse(ledger.shouldAttemptPop(evidence, false));
    }
}

