# Engineering Principles

## 1. Think Before Coding
*Don't assume. Don't hide confusion. Surface tradeoffs.*

Before implementing:
* **State assumptions:** State your assumptions explicitly. If uncertain, ask.
* **Present options:** If multiple interpretations exist, present them — don't pick silently.
* **Push back:** If a simpler approach exists, say so. Push back when warranted.
* **Stop on ambiguity:** If something is unclear, stop. Name what's confusing. Ask.

---

## 2. Simplicity First
*Minimum code that solves the problem. Nothing speculative.*

* **No bloat:** No features beyond what was asked.
* **No premature abstraction:** No abstractions for single-use code.
* **No hypothetical flexibility:** No "flexibility" or "configurability" that wasn't requested.
* **No over-engineering:** No error handling for impossible scenarios.
* **Refactor down:** If you write 200 lines and it could be 50, rewrite it.

> **The Litmus Test:** Ask yourself: *"Would a senior engineer say this is overcomplicated?"* If yes, simplify.

---

## 3. Surgical Changes
*Touch only what you must. Clean up only your own mess.*

### When Editing Existing Code
* Don't "improve" adjacent code, comments, or formatting.
* Don't refactor things that aren't broken.
* Match existing style, even if you'd do it differently.
* If you notice unrelated dead code, mention it — don't delete it.

### When Your Changes Create Orphans
* Remove imports, variables, or functions that **your** changes made unused.
* Don't remove pre-existing dead code unless asked.

> **The Test:** Every changed line should trace directly to the user's request.

---

## 4. Goal-Driven Execution
*Define success criteria. Loop until verified.*

Transform tasks into verifiable goals:
* **"Add validation"** $\rightarrow$ Write tests for invalid inputs, then make them pass.
* **"Fix the bug"** $\rightarrow$ Write a test that reproduces it, then make it pass.
* **"Refactor X"** $\rightarrow$ Ensure tests pass before and after.

### Execution Plan Template
For multi-step tasks, state a brief plan:
1. **[Step 1]** $\rightarrow$ *Verify:* [check]
2. **[Step 2]** $\rightarrow$ *Verify:* [check]
3. **[Step 3]** $\rightarrow$ *Verify:* [check]

*Note: Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.*

---

## Metric of Success
These guidelines are working if you notice:
* Fewer unnecessary changes in diffs
* Fewer rewrites due to overcomplication
* Clarifying questions coming **before** implementation rather than after mistakes

- Always create test cases for the generated code both positive and negative.
- Minimize the amount of code generated.
- Update README.md each time you generate a new version.
- When needed to implement a new feature, go to main branch and pull the latest changes and then create a new branch from it, do the necesseries commits while developing, test the feature and push it to a remote branch.
- Always ask me questions to get more context if you think that you need more information to complete the task or make a better implementation.
- When I ask for a .md file, always put it in /docs.
- Always when you finish some task on a .md mark them as completed.