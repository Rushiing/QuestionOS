#!/usr/bin/env node

import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import ts from 'typescript';

const calibration = loadTypeScriptModule('lib/calibration-json-to-markdown.ts');
const integrator = loadTypeScriptModule('lib/integrator-markdown.ts');

test('calibration JSON renders the first Decision question and stable phase', () => {
  const output = calibration.formatCalibrationJsonToMarkdown(`\`\`\`json
  {
    "calibration_mode": "decision",
    "phase": "synthesis",
    "questions": ["这是你当下的真实判断吗？", "这条不应展示"],
    "user_conclusion_mirror": "你愿意先做可回滚的小实验。",
    "reasoning": "确认结论来自用户。"
  }
  \`\`\``);
  assert.match(output, /## 本轮追问/);
  assert.match(output, /\*结论回放\*/);
  assert.match(output, /你的结论（回放）/);
  assert.match(output, /这是你当下的真实判断吗/);
  assert.doesNotMatch(output, /这条不应展示/);
});

test('malformed calibration payload remains untouched', () => {
  assert.equal(calibration.formatCalibrationJsonToMarkdown('不是 JSON'), '不是 JSON');
});

test('integrator expert claims become separate bullets without damaging the decision table', () => {
  const input = `## 博弈复盘

**利益审计师**：先验证成本。 **风险预测官**：保留回滚。 **价值裁判**：确认底线。

### 📊 决策沙盘

| 维度 | 判断 |
|---|---|
| 风险 | 可回滚 |`;
  const output = integrator.normalizeIntegratorExpertBullets(input);
  assert.match(output, /- \*\*利益审计师\*\*/);
  assert.match(output, /- \*\*风险预测官\*\*/);
  assert.match(output, /- \*\*价值裁判\*\*/);
  assert.match(output, /### 📊 决策沙盘/);
  assert.match(output, /\| 风险 \| 可回滚 \|/);
});

function loadTypeScriptModule(relativePath) {
  const filename = resolve(relativePath);
  const source = readFileSync(filename, 'utf8');
  const compiled = ts.transpileModule(source, {
    compilerOptions: {
      module: ts.ModuleKind.CommonJS,
      target: ts.ScriptTarget.ES2020,
      esModuleInterop: true,
    },
    fileName: filename,
    reportDiagnostics: true,
  });
  if (compiled.diagnostics?.length) {
    const messages = compiled.diagnostics.map((item) => ts.flattenDiagnosticMessageText(item.messageText, '\n'));
    throw new Error(`Cannot transpile ${relativePath}: ${messages.join('; ')}`);
  }
  const module = { exports: {} };
  Function('exports', 'module', 'require', compiled.outputText)(module.exports, module, () => {
    throw new Error(`${relativePath} unexpectedly imported another module`);
  });
  return module.exports;
}

function test(name, fn) {
  try {
    fn();
    console.log(`ok - ${name}`);
  } catch (error) {
    console.error(`not ok - ${name}`);
    throw error;
  }
}
