import { buildOpenApiDocument } from "./app.ts";

const outPath = new URL("../../../../contract/openapi.json", import.meta.url);
const doc = buildOpenApiDocument();
await Deno.writeTextFile(outPath, `${JSON.stringify(doc, null, 2)}\n`);
