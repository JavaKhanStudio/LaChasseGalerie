// tablocal.mjs <url> <outdir> — r102: a phone plays Local play alone, by touch (run by tablocal.sh).
//
// A Pixel 7 on its side, as tabtouch.mjs: every tap is a real touch event at the place the game drew what it
// taps (lcg.find). The tap on Local play is the phone's join (d9): the run must start with its hero in and the
// pad on screen. Right is held until the hero walks off the canoe and drowns; then "Tap to join" must show and
// a tap on the bare river (Simon on r102: click to resurrect) must bring a hero back. The pad's ‖ must open the
// pause, whose Resume, tapped, closes it. Writes local_1_menu.png .. local_5_paused.png and local.json.
import puppeteer from 'puppeteer-core';
import { writeFileSync } from 'node:fs';

const [url, out] = process.argv.slice(2);
const chrome = process.env.CHROME || '/usr/bin/google-chrome';
const say = (line) => console.log('local: ' + line);
const fail = (why) => { console.error('local: FAILED — ' + why); process.exitCode = 1; };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const phone = { width: 915, height: 412, deviceScaleFactor: 2, isMobile: true, hasTouch: true, isLandscape: true };
const browser = await puppeteer.launch({
	executablePath: chrome,
	headless: true,
	args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--autoplay-policy=no-user-gesture-required',
		'--no-first-run', '--no-default-browser-check'],
});
const report = { url, phone, console: [], taps: [], samples: [] };
const local = async (page) => {
	const text = await page.evaluate(() => window.lcg.local());
	const [heroes, x, deaths, paused] = text.split(' ');
	return { text, heroes: Number(heroes), x: x === '-' ? null : Number(x), deaths: Number(deaths), paused: paused === 'true' };
};
// Where the game drew this actor, in the page's CSS pixels : lcg.find is in the canvas's own 1280x720
const where = async (page, key) => page.evaluate((key) => {
	const found = window.lcg.find(key);
	if (!found) return null;
	const [x, y, w, h] = found.split(' ').map(Number);
	const canvas = document.querySelector('canvas');
	const box = canvas.getBoundingClientRect();
	const sx = box.width / canvas.width, sy = box.height / canvas.height;
	return { x: box.x + (x + w / 2) * sx, y: box.y + (y + h / 2) * sy, w: w * sx, h: h * sy };
}, key);
const find = async (page, key, timeout = 5000) => {
	for (const t0 = Date.now(); Date.now() - t0 < timeout; await sleep(100)) {
		const at = await where(page, key);
		if (at) return at;
	}
	throw new Error(`nothing drawn for "${key}" on ${await page.evaluate(() => window.lcg.vue())}`);
};
const tap = async (page, key, at) => {
	at = at || await find(page, key);
	report.taps.push({ key, x: Math.round(at.x), y: Math.round(at.y) });
	await page.touchscreen.tap(at.x, at.y);
	return at;
};
const until = async (page, test, ms, what) => {
	for (const t0 = Date.now(); Date.now() - t0 < ms; await sleep(100)) {
		const now = await local(page);
		if (test(now)) return now;
	}
	throw new Error(`${what}: ${(await local(page)).text}`);
};

try {
	const page = await browser.newPage();
	await page.setViewport(phone);
	page.on('console', (m) => report.console.push(m.type() + ': ' + m.text()));
	page.on('pageerror', (e) => report.console.push('pageerror: ' + e.message));

	await page.goto(url, { waitUntil: 'load' });
	await page.waitForFunction(() => window.lcg && window.lcg.frames() > 30 && window.lcg.vue().endsWith('Vue_Menu'), { timeout: 60000, polling: 100 });
	await page.screenshot({ path: `${out}/local_1_menu.png` });

	// The tap that picks the run is the phone's join (d9) : no second tap to get in
	await tap(page, 'Local play');
	await page.waitForFunction(() => window.lcg.vue().endsWith('Vue_Game'), { timeout: 5000, polling: 50 });
	const joined = await until(page, (s) => s.heroes === 1, 2000, 'the tap on Local play did not put a hero in the run');
	report.samples.push(joined.text);
	say(`tapped Local play: in the run, ${joined.text}`);
	await sleep(300);
	if (await where(page, 'tap-to-join')) fail('"Tap to join" shows with the hero in the run');
	await find(page, 'touch-jump');
	await find(page, 'touch-pause');
	await sleep(1500); // it lands
	const landed = await local(page);
	report.samples.push(landed.text);

	// A thumb on Right, held : the hero walks, and on off the end of the canoe into the river
	const right = await find(page, 'touch-right');
	await page.touchscreen.touchStart(right.x, right.y);
	await sleep(400);
	await page.screenshot({ path: `${out}/local_2_run.png` });
	const walking = await local(page);
	report.samples.push(walking.text);
	if (!(walking.x !== null && walking.x - landed.x > 0.3)) fail(`the hero did not walk right under the thumb: ${landed.x} -> ${walking.x}`);
	const drowned = await until(page, (s) => s.deaths >= 1 && s.heroes === 0, 10000, 'the hero never drowned off the canoe');
	await page.touchscreen.touchEnd();
	report.samples.push(drowned.text);
	say(`walked ${landed.x} -> ${walking.x}, then drowned: ${drowned.text}`);

	// Dead : the phone is told what to do, and a tap on the bare river does it
	await sleep(500);
	if (!(await where(page, 'tap-to-join'))) fail('"Tap to join" is not shown once the hero is gone');
	await page.screenshot({ path: `${out}/local_3_dead.png` });
	const middle = { x: phone.width / 2, y: phone.height * 0.35 };
	await tap(page, 'the river', middle);
	const back = await until(page, (s) => s.heroes === 1, 2000, 'a tap on the river did not bring the hero back');
	report.samples.push(back.text);
	say(`tapped the river: ${back.text}`);
	await sleep(1200);
	await page.screenshot({ path: `${out}/local_4_back.png` });
	if (await where(page, 'tap-to-join')) fail('"Tap to join" still shows once the hero is back');

	// With a hero the river takes no tap : a second one must not add anyone
	await tap(page, 'the river again', middle);
	await sleep(300);
	if ((await local(page)).heroes !== 1) fail(`a tap with a hero on the canoe changed the heroes: ${(await local(page)).text}`);

	// The pad's ‖ is Escape : the pause, then Resume
	await tap(page, 'touch-pause');
	const paused = await until(page, (s) => s.paused, 2000, 'the pad\'s pause did not open the pause');
	await sleep(300);
	await page.screenshot({ path: `${out}/local_5_paused.png` });
	if (await where(page, 'touch-jump')) fail('the touch pad is still drawn under the pause');
	await tap(page, 'Resume');
	await until(page, (s) => !s.paused, 2000, 'Resume did not close the pause');
	await sleep(300);
	await find(page, 'touch-jump');
	say(`paused (${paused.text}) and resumed with the pad back`);

	const errors = report.console.filter((l) => l.startsWith('pageerror') || l.startsWith('error'));
	if (errors.length) say(`console errors (not fatal):\n  ${errors.join('\n  ')}`);
} catch (e) {
	fail(e.message);
	try { await (await browser.pages())[1]?.screenshot({ path: `${out}/local_fail.png` }); } catch {}
} finally {
	writeFileSync(`${out}/local.json`, JSON.stringify(report, null, 2));
	await browser.close();
}
