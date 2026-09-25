// gate.mjs <url> <outdir> — the browser gate's driver, run by gate.sh.
//
// Drives the page the way a player would, in HEADLESS Chrome (never a window on anyone's screen):
// waits for the game to run, presses a real key, and fails unless a hero joined. It drives the
// browser with puppeteer-core against the system Chrome, NOT `chrome --screenshot`, which pumps
// about five animation frames and then shoots a game that looks frozen (docs/browser-target.md
// section 10). The two screenshots and gate.json land in <outdir>.
import puppeteer from 'puppeteer-core';
import { writeFileSync } from 'node:fs';

const [url, out] = process.argv.slice(2);
const chrome = process.env.CHROME || '/usr/bin/google-chrome';
const say = (line) => console.log('gate: ' + line);
const fail = (why) => { console.error('gate: FAILED — ' + why); process.exitCode = 1; };

const browser = await puppeteer.launch({
	executablePath: chrome,
	headless: true,
	// No GPU is assumed: SwiftShader is software WebGL, and still ~58 fps on this game (section 10)
	args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--autoplay-policy=no-user-gesture-required',
		'--window-size=1280,720', '--no-first-run', '--no-default-browser-check'],
	defaultViewport: { width: 1280, height: 720 },
});
const report = { url, console: [] };
try {
	const page = await browser.newPage();
	page.on('console', (m) => report.console.push(m.type() + ': ' + m.text()));
	page.on('pageerror', (e) => report.console.push('pageerror: ' + e.message));

	const t0 = Date.now();
	await page.goto(url, { waitUntil: 'load' });
	// The preloader pulls 32 MB of assets before the first frame; on localhost that is ~2 s
	await page.waitForFunction(() => window.lcg && window.lcg.frames() > 30, { timeout: 60000, polling: 100 });
	report.firstFramesMs = Date.now() - t0;
	say(`running after ${report.firstFramesMs} ms`);

	report.heroesBefore = await page.evaluate(() => window.lcg.heroes());
	await page.screenshot({ path: `${out}/before.png` });

	// A real key event through the browser, not a call into the game: the first key joins a player
	await page.keyboard.press('ArrowRight');
	await page.waitForFunction(() => window.lcg.heroes() > 0, { timeout: 5000, polling: 50 }).catch(() => {});
	report.heroesAfter = await page.evaluate(() => window.lcg.heroes());

	// Let the hero land and the loop show its pace: frames over 3 s of wall clock
	const f0 = await page.evaluate(() => window.lcg.frames());
	await new Promise((r) => setTimeout(r, 3000));
	const f1 = await page.evaluate(() => window.lcg.frames());
	report.fps = Math.round((f1 - f0) / 3);
	await page.screenshot({ path: `${out}/joined.png` });
	say(`heroes ${report.heroesBefore} -> ${report.heroesAfter}, ${report.fps} fps`);

	if (report.heroesBefore !== 0) fail(`${report.heroesBefore} heroes before any key was pressed`);
	if (report.heroesAfter < 1) fail('a key press did not join a player');
	// A page stuck on a handful of frames is what --screenshot shows; a running game is far above this
	if (report.fps < 20) fail(`${report.fps} fps: the game loop is not running`);
	const errors = report.console.filter((l) => l.startsWith('pageerror') || l.startsWith('error'));
	if (errors.length) say(`console errors (not fatal):\n  ${errors.join('\n  ')}`);
} catch (e) {
	fail(e.message);
} finally {
	writeFileSync(`${out}/gate.json`, JSON.stringify(report, null, 2));
	await browser.close();
}
