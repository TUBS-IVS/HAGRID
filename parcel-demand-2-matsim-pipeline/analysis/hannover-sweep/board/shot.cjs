// Headless visual check of bundle.html via installed Edge (playwright-core).
const { chromium } = require("playwright-core");
const path = require("path");

(async () => {
  const b = await chromium.launch({ channel: "msedge" });
  const page = await b.newPage({ viewport: { width: 1400, height: 1000 } });
  const errors = [];
  page.on("console", (m) => {
    if (m.type() === "error") errors.push(m.text());
  });
  page.on("pageerror", (e) => errors.push(String(e)));
  await page.goto("file:///" + path.join(__dirname, "bundle.html").replace(/\\/g, "/"));
  await page.waitForTimeout(2500);
  await page.screenshot({ path: "shot_top.png" });
  await page.evaluate(() => window.scrollTo(0, 1100));
  await page.waitForTimeout(400);
  await page.screenshot({ path: "shot_mid.png" });
  await page.evaluate(() => window.scrollTo(0, 2300));
  await page.waitForTimeout(400);
  await page.screenshot({ path: "shot_low.png" });
  console.log("ERRORS:", JSON.stringify(errors.slice(0, 5)));
  await b.close();
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
