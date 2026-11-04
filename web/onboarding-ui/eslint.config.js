import js from '@eslint/js';
import globals from 'globals';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  { ignores: ['dist', 'node_modules', 'src/api/schema.d.ts', 'test-results', 'playwright-report', 'screenshots', '.pytest_cache'] },
  {
    extends: [js.configs.recommended, ...tseslint.configs.strict],
    files: ['**/*.{ts,tsx}'],
    languageOptions: { ecmaVersion: 2022, globals: { ...globals.browser, ...globals.node } },
    plugins: { 'react-hooks': reactHooks, 'react-refresh': reactRefresh },
    rules: {
      ...reactHooks.configs.recommended.rules,
      // The app intentionally co-locates tiny router/i18n helpers with providers; HMR safety is not a release concern.
      'react-refresh/only-export-components': 'off',
      // No hand-written API types: screens use the generated client (src/api).
      'no-restricted-imports': ['error', { patterns: [{ group: ['**/api/schema'], message: 'Import API types from src/api/types.' }] }],
    },
  },
  {
    files: ['src/api/types.ts'],
    rules: { 'no-restricted-imports': 'off' },
  },
  {
    files: ['src/api/client.ts'],
    rules: { 'no-restricted-imports': 'off' },
  },
);
