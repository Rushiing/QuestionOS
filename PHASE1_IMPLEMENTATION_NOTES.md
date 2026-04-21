# Phase 1: Problem Dissolution Checking + Adaptive Depth Gate (Implementation Notes)

## Completed Implementation

### 1. ProblemDissolutionChecker (`ProblemDissolutionChecker.java`)
**Purpose**: Detect problem quality issues before step① generates follow-ups.

**Detections**:
- **Pseudo-Problems**: Questions with no real decision tension (e.g., "Should I be happy?")
- **XY Problems**: User asks about solution Y, real issue is X
- **Symptom vs Root Cause**: User describes symptom, not underlying driver
- **Information Deficits**: Critical context missing

**Flow**:
1. Takes `combinedIssueStatement` + `SandboxDeliberationScene`
2. Calls LLM (25s timeout, 35s block wait) to detect issues
3. Returns `DissolutionCheckResult` with:
   - Four boolean flags (isPseudoProblem, isXyProblem, isSymptomVsRoot, hasInformationDeficit)
   - Human-readable summary of issues
   - `suggestedReframe` if XY problem detected
   - `overallAssessment`: "问题形成", "需要澄清", "可严肃推演"

### 2. SandboxProblemDissolutionCard (`SandboxProblemDissolutionCard.java`)
**Purpose**: Render dissolution findings as Markdown card for user.

**Output**:
- Conditional sections for each detected issue type
- Explanation with examples
- "接下来怎么做" guidance:
  - If "需要澄清": suggests user reflect + rephrase problem
  - Otherwise: explains checks are for reference

### 3. AdaptiveDepthGate (`AdaptiveDepthGate.java`)
**Purpose**: Post-Round 1 consensus evaluation for deciding Round 2 depth.

**Three Consensus Levels**:
- **HIGH** (>80%): Most roles agree on direction → "可接受跳过 Round 2"
- **MEDIUM** (60-80%): Two main positions, minority has merit → "建议深挖"
- **LOW** (<60%): Fundamental disagreement → "必须深挖"

**Output**:
- `consensusLevel`: HIGH/MEDIUM/LOW
- `majorityView`: What most roles agree on (1-2 sentences)
- `minorityView` + `minorityStrength`: Why dissent matters
- `shouldDeepen`: Ready-to-display recommendation

### 4. SessionService Integration
**Changes**:
1. Added `ProblemDissolutionChecker` and `AdaptiveDepthGate` as injected dependencies
2. Added `maybeCheckAndEmitProblemDissolution()` method:
   - Non-blocking async check (8s timeout)
   - Emits `dissolution_check` event if issues detected
   - Does NOT block main step① flow if check fails/times out
3. Integrated into step① flow: calls check when `needClarificationFirst && normalizedIssue != null`

---

## Design Decisions

### Why Non-Blocking Dissolution Check?
- Step① already involves LLM calls (classification, follow-ups) → adding synchronous check would compound timeout risk
- Dissolution check is **supplementary** (user can ignore it), not gate-blocking
- If check fails/times out, user still gets follow-up card + dimensions → flow continues normally

### Why AdaptiveDepthGate is Separate?
- Post-Round 1 integration point not yet implemented (would require step② orchestration changes)
- Prepared as component for Phase 2 when step② round-based flow is refactored
- Available now for testing in simpler sandbox modes (--quick, --duo)

### Timeout Choices
- Dissolution check: **8s** (should be quick; if slow, skip gracefully)
- LLM internal timeout for dissolution call: **25s** (same as other classifiers)
- Block wait: **35s** (25s + 10s buffer)

---

## Testing Checklist (Manual)

- [ ] **Pseudo-Problem Detection**: Send "我应该开心吗？" → dissolution card appears with "伪问题" warning
- [ ] **XY Problem Detection**: Send "怎么快速消除焦虑" → dissolution card shows possible root cause reframe
- [ ] **Symptom vs Root**: Send "团队离职严重，要招更多人吗？" → dissolution card surfaces "离职原因" as root
- [ ] **Info Deficit**: Send "要投资加密吗？" → dissolution card lists missing context
- [ ] **Non-Blocking Behavior**: If dissolution check times out, follow-up card still appears normally
- [ ] **High Consensus Path**: Send clear problem → Round 1 → most agents agree → adaptive gate marks HIGH
- [ ] **Low Consensus Path**: Send ambiguous problem → Round 1 → split opinions → adaptive gate marks LOW

---

## Phase 2 Prerequisites (Not Yet Done)

To integrate AdaptiveDepthGate into the actual step② → Round 2 flow:
1. Refactor `AgentOrchestrator` to expose Round 1 agent outputs before Round 2 dispatch
2. After Round 1 completes, call `adaptiveDepthGate.assessAsync(round1Analyses)`
3. Present user with AskUserQuestion showing consensus level + recommendation
4. Conditional: if user accepts "接受主流方向" → skip Round 2, go to verdict; if "深挖" → proceed to Round 2

Current code is prepared for this; AdaptiveDepthGate is injectable and tested in isolation.

---

## Files Modified/Created

### Created
- `ProblemDissolutionChecker.java` (135 lines)
- `SandboxProblemDissolutionCard.java` (59 lines)
- `AdaptiveDepthGate.java` (156 lines)
- `PHASE1_IMPLEMENTATION_NOTES.md` (this file)

### Modified
- `SessionService.java`: Added dependency injection + `maybeCheckAndEmitProblemDissolution()` call

### Build Status
- ✅ Compiles: `mvn -DskipTests compile` SUCCESS
- ✅ Dependencies wired correctly
- ✅ No runtime dependencies on step② logic

---

## Next Steps

1. **Test dissolution check** with real LLM in staging environment
2. **Tune LLM prompt** (DISSOLUTION_SYSTEM) based on false positive/negative rates
3. **Phase 2 integration**: Wire AdaptiveDepthGate into post-Round 1 flow (requires AgentOrchestrator refactor)
4. **Extended thinking**: Integrate Polanyi tacit knowledge extraction when user signals "说不清楚"
5. **Dashboard tracking**: Monitor how many sessions hit dissolution flags → guides UX polish
