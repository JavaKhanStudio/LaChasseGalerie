// tabtouch.mjs <url> <outdir> — tabjoin.mjs on a PHONE (r87): the same run, driven by touch alone.
//
// Headless Chrome emulating a phone held sideways (915x412 CSS px, touch, no mouse). Every tap is a real
// touch event (page.touchscreen) at the place the game drew what it taps, read from window.lcg.find() and
// scaled into the page, so a canvas that is not fitted or a touch mapped wrong misses. On the menu it taps
// Online play; on the lobby screen it taps the code field, which must open the soft keyboard (a hidden
// input: lcg.touch()), types the host's code into it and checks the field shows it, then taps the host's
// game under Open games. Once in, the touch pad must be on screen: it taps Jump for a hero, holds Right, taps
// Jump and both swings. Writes phone_1_menu.png, phone_2_code.png, phone_3_run.png, phone_4_portrait.png and
// tab.json, then <outdir>/tab_done so the host probe stops. GO=1 joins with the keyboard's Go key instead.
import puppeteer from 'puppeteer-core';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';

const [url, out] = process.argv.slice(2);
const chrome = process.env.CHROME || '/usr/bin/google-chrome';
const say = (line) => console.log('tab: ' + line);
const fail = (why) => { console.error('tab: FAILED — ' + why); process.exitCode = 1; };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// A Pixel 7 on its side
const phone = { width: 915, height: 412, deviceScaleFactor: 2, isMobile: true, hasTouch: true, isLandscape: true };
const browser = await puppeteer.launch({
	executablePath: chrome,
	headless: true,
	args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--autoplay-policy=no-user-gesture-required',
		'--no-first-run', '--no-default-browser-check'],
});
const report = { url, phone, console: [], samples: [], taps: [] };
const online = async (page) => {
	const text = await page.evaluate(() => window.lcg.online());
	const [state, player, snapshots, x, axe] = text.split(' ');
	const number = (v) => v === undefined || v === '-' ? null : Number(v);
	return { text, state, player: Number(player), snapshots: Number(snapshots), x: number(x), axe: number(axe) };
};
// Where the game drew this actor, in the page's CSS pixels : lcg.find is in the canvas's own 1280x720
const where = async (page, key) => page.evaluate((key) => {
	const found = window.lcg.find(key);
	if (!found) return null;
	const [x, y, w, h, ...text] = found.split(' ');
	const canvas = document.querySelector('canvas');
	const box = canvas.getBoundingClientRect();
	const sx = box.width / canvas.width, sy = box.height / canvas.height;
	return { x: box.x + (+x + w / 2) * sx, y: box.y + (+y + h / 2) * sy, w: w * sx, h: h * sy, text: text.join(' ') };
}, key);
const find = async (page, key, timeout = 5000) => {
	for (const t0 = Date.now(); Date.now() - t0 < timeout; await sleep(100)) {
		const at = await where(page, key);
		if (at) return at;
	}
	throw new Error(`nothing drawn for "${key}" on ${await page.evaluate(() => window.lcg.vue())}`);
};
const tap = async (page, key) => {
	const at = await find(page, key);
	report.taps.push({ key, x: Math.round(at.x), y: Math.round(at.y), w: Math.round(at.w), h: Math.round(at.h) });
	await page.touchscreen.tap(at.x, at.y);
	return at;
};

run: try {
	const page = await browser.newPage();
	await page.setViewport(phone);
	page.on('console', (m) => report.console.push(m.type() + ': ' + m.text()));
	page.on('pageerror', (e) => report.console.push('pageerror: ' + e.message));

	await page.goto(url, { waitUntil: 'load' });
	await page.waitForFunction(() => window.lcg && window.lcg.frames() > 30 && window.lcg.vue().endsWith('Vue_Menu'), { timeout: 60000, polling: 100 });
	// The canvas fits the phone, whole : not 1280 px of it scrolled off the side
	report.canvas = await page.evaluate(() => {
		const box = document.querySelector('canvas').getBoundingClientRect();
		return { x: box.x, y: box.y, width: box.width, height: box.height, pageWidth: document.documentElement.scrollWidth, innerWidth };
	});
	const c = report.canvas;
	if (c.x < 0 || c.y < 0 || c.x + c.width > phone.width + 0.5 || c.y + c.height > phone.height + 0.5 || c.pageWidth > phone.width)
		throw new Error(`the canvas does not fit the phone: ${JSON.stringify(c)}`);
	say(`on the menu, canvas ${c.width.toFixed(0)}x${c.height.toFixed(0)} at ${c.x.toFixed(0)},${c.y.toFixed(0)} on ${phone.width}x${phone.height}`);
	await page.screenshot({ path: `${out}/phone_1_menu.png` });

	await tap(page, 'Online play');
	await page.waitForFunction(() => window.lcg.vue().endsWith('Vue_Lobby'), { timeout: 5000, polling: 50 });
	say('tapped Online play');

	const codeFile = `${out}/code.txt`;
	for (let i = 0; !existsSync(codeFile); i++) {
		if (i > 600) throw new Error('the host never wrote its code');
		await sleep(100);
	}
	const code = readFileSync(codeFile, 'utf8').trim();

	// The phone's own keyboard : tapping the code opens it, and what it types shows in the field
	await tap(page, 'code');
	await sleep(200);
	const [seen, keyboard] = (await page.evaluate(() => window.lcg.touch())).split(' ');
	report.touchSeen = seen === 'true';
	if (keyboard !== 'true') throw new Error('tapping the code field did not open the soft keyboard (its input has no focus)');
	await page.keyboard.type(code.toLowerCase(), { delay: 40 });
	await sleep(300);
	const field = (await where(page, 'code')).text.replace(/ /g, '');
	report.typed = field;
	if (field !== code) throw new Error(`the soft keyboard typed ${code.toLowerCase()} and the field shows "${field}"`);
	say(`typed ${code} on the soft keyboard`);

	// The host's game under Open games, which the tab asks for every 3 s
	const spaced = code.slice(0, 3) + ' ' + code.slice(3);
	await find(page, spaced + ' ', 8000);
	await page.screenshot({ path: `${out}/phone_2_code.png` });
	if (process.env.GO) {
		// GO=1 : the soft keyboard's own Go key joins the code typed, instead of a tap on the list
		await page.keyboard.press('Enter');
		say('pressed Go on the soft keyboard');
	} else {
		await tap(page, spaced + ' ');
		say(`tapped ${spaced} under Open games`);
	}
	await sleep(300);
	if ((await page.evaluate(() => window.lcg.touch())).split(' ')[1] !== 'false') fail('the soft keyboard stayed open after joining');

	const t0 = Date.now();
	await page.waitForFunction(() => window.lcg.vue().endsWith('Vue_Client'), { timeout: 60000, polling: 100 });
	report.inAfterMs = Date.now() - t0;
	say(`let in after ${report.inAfterMs} ms`);

	// The first tap asks for a hero, as a key does
	await sleep(1000);
	await tap(page, 'touch-jump');
	for (let i = 0; (await online(page)).x === null; i++) {
		if (i > 100) throw new Error('no hero of this tab in the snapshots: ' + (await online(page)).text);
		await sleep(100);
	}
	await sleep(1500); // it lands
	const landed = await online(page);
	report.samples.push(landed.text);

	// A thumb on Right, held half a second : longer, and the hero walks off the end of the canoe and drowns
	const right = await find(page, 'touch-right');
	await page.touchscreen.touchStart(right.x, right.y);
	let farthest = landed.x;
	for (let i = 0; i < 5; i++) {
		await sleep(100);
		const now = await online(page);
		report.samples.push(now.text);
		if (now.x !== null) farthest = Math.max(farthest, now.x);
		if (i === 3) await page.screenshot({ path: `${out}/phone_3_run.png` });
	}
	await page.touchscreen.touchEnd();
	await sleep(1000);
	const walked = await online(page);
	report.samples.push(walked.text);
	if (walked.x !== null) farthest = Math.max(farthest, walked.x);
	// It stopped when the thumb lifted
	await sleep(600);
	const still = await online(page);
	report.stoppedAt = [walked.x, still.x];

	// A swing is seen in the axe's angle : how far it turns in the 800 ms after a tap, against 800 ms of nothing.
	// Right first : an axe at rest after the walk right hangs where a swing left does not stir it (r87's run)
	const turn = async () => {
		const angles = [];
		for (let i = 0; i < 8; i++) { await sleep(100); angles.push((await online(page)).axe); }
		return Math.max(...angles) - Math.min(...angles);
	};
	report.axeTurn = { idle: await turn() };
	for (const key of ['touch-swing-right', 'touch-swing-left']) {
		await tap(page, key);
		report.axeTurn[key] = await turn();
	}
	say(`the axe turned ${JSON.stringify(report.axeTurn)} rad`);
	for (const key of ['touch-swing-left', 'touch-swing-right'])
		if (!(report.axeTurn[key] > report.axeTurn.idle + 0.3)) fail(`${key} did not swing the axe: ${JSON.stringify(report.axeTurn)}`);

	report.player = walked.player;
	report.snapshots = walked.snapshots;
	report.fromX = landed.x;
	report.farthestX = farthest;
	say(`player ${walked.player}, ${walked.snapshots} snapshots, x ${landed.x} -> ${farthest}, then ${walked.x} -> ${still.x} with the thumb off`);

	if (walked.state !== 'IN') fail(`the session is ${walked.state}`);
	if (walked.snapshots < 40) fail(`${walked.snapshots} snapshots drawn`);
	if (!(farthest - landed.x > 0.5)) fail(`the hero did not walk right under the thumb: ${landed.x} -> ${farthest}`);
	if (walked.x === null || still.x === null) fail(`the hero is gone after the walk: ${walked.x} -> ${still.x}`);
	else if (Math.abs(still.x - walked.x) > 0.3) fail(`the hero went on walking after the thumb lifted: ${walked.x} -> ${still.x}`);

	// The same page held upright, for the record : a 16:9 game on a tall screen
	await page.setViewport({ ...phone, width: phone.height, height: phone.width, isLandscape: false });
	await sleep(800);
	await page.screenshot({ path: `${out}/phone_4_portrait.png` });

	const errors = report.console.filter((l) => l.startsWith('pageerror') || l.startsWith('error'));
	if (errors.length) say(`console errors (not fatal):\n  ${errors.join('\n  ')}`);
} catch (e) {
	fail(e.message);
	try { await (await browser.pages())[1]?.screenshot({ path: `${out}/tab_fail.png` }); } catch {}
} finally {
	writeFileSync(`${out}/tab.json`, JSON.stringify(report, null, 2));
	writeFileSync(`${out}/tab_done`, '');
	await browser.close();
}
