import { existsSync } from 'node:fs';
import { execFileSync } from 'node:child_process';

if (!existsSync('openapi.json') || !existsSync('src/api/schema.d.ts')) {
  throw new Error('openapi.json and its generated src/api/schema.d.ts are both required');
}
execFileSync(process.execPath, ['node_modules/openapi-typescript/bin/cli.js', 'openapi.json', '-o', 'src/api/schema.d.ts'], { stdio: 'inherit' });
console.log('Generated API contract is current.');
