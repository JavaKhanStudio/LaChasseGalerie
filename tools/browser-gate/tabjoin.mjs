// tabjoin.mjs <url> <outdir> — the browser tab of tabjoin.sh (r82), in HEADLESS Chrome.
//
// Plays Online play the way a person in a tab would, with real key events: Down and Enter on the menu,
// the host's code typed into the lobby screen (from <outdir>/code.txt, which the host probe writes), Enter
// to join. Then waits for the host to start, presses Space for a hero and holds Right, reading
// window.lcg.online() ("state player snapshots x"). Writes tab_1_lobby.png, tab_2_joining.png,
// tab_3_run.png and tab.json, then <outdir>/tab_done so the host probe stops. REFUSED=1 : tab_2_refused.png, and
// the tab must still be on its lobby screen 4.5 s after Enter.
import puppeteer from 'puppeteer-core';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';

const [url, out] = process.argv.slice(2);
const chrome = process.env.CHROME || '/usr/bin/google-chrome';
const say = (line) => console.log('tab: ' + line);
const fail = (why) => { console.error('tab: FAILED — ' + why); process.exitCode = 1; };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const browser = await puppeteer.launch({
	executablePath: chrome,
	headless: true,
	args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--autoplay-policy=no-user-gesture-required',
		'--window-size=1280,720', '--no-first-run', '--no-default-browser-check'],
	defaultViewport: { width: 1280, height: 720 },
});
const report = { url, console: [], samples: [] };
const online = async (page) => {
	const text = await page.evaluate(() => window.lcg.online());
	const [state, player, snapshots, x] = text.split(' ');
	return { text, state, player: Number(player), snapshots: Number(snapshots), x: x === undefined || x === '-' ? null : Number(x) };
};
run: try {
	const page = await browser.newPage();
	page.on('console', (m) => report.console.push(m.type() + ': ' + m.text()));
	page.on('pageerror', (e) => report.console.push('pageerror: ' + e.message));

	await page.goto(url, { waitUntil: 'load' });
	await page.waitForFunction(() => window.lcg && window.lcg.frames() > 30 && window.lcg.vue().endsWith('Vue_Menu'), { timeout: 60000, polling: 100 });
	say('on the menu');
	// Online play is the second choice
	await page.keyboard.press('ArrowDown');
	await page.keyboard.press('Enter');
	await page.waitForFunction(() => window.lcg.vue().endsWith('Vue_Lobby'), { timeout: 5000, polling: 50 });

	const codeFile = `${out}/code.txt`;
	for (let i = 0; !existsSync(codeFile); i++) {
		if (i > 600) throw new Error('the host never wrote its code');
		await sleep(100);
	}
	const code = readFileSync(codeFile, 'utf8').trim();
	// Let Open games come round with the host's lobby in it: the tab asks every 3 s
	await sleep(3500);
	await page.screenshot({ path: `${out}/tab_1_lobby.png` });
	say(`typing ${code}`);
	await page.keyboard.type(code.toLowerCase(), { delay: 40 });
	await sleep(300);
	await page.keyboard.press('Enter');
	if (process.env.REFUSED) {
		// r85 : a host with no WebRTC. The service says NO_TABS at the JOIN : the tab stays on its lobby screen and says why
		await sleep(1500);
		await page.screenshot({ path: `${out}/tab_2_refused.png` });
		await sleep(3000);
		report.vue = await page.evaluate(() => window.lcg.vue());
		if (!report.vue.endsWith('Vue_Lobby')) fail(`a refused tab left its lobby screen for ${report.vue}`);
		else say('refused, and still on its lobby screen');
		break run;
	}
	await sleep(1500);
	await page.screenshot({ path: `${out}/tab_2_joining.png` });

	// The host starts once it has seen the tab's channel open
	const t0 = Date.now();
	await page.waitForFunction(() => window.lcg.vue().endsWith('Vue_Client'), { timeout: 60000, polling: 100 });
	report.inAfterMs = Date.now() - t0;
	say(`let in after ${report.inAfterMs} ms`);

	// Any key asks for a hero; then walk right
	await sleep(1000);
	await page.keyboard.press('Space');
	for (let i = 0; (await online(page)).x === null; i++) {
		if (i > 100) throw new Error('no hero of this tab in the snapshots: ' + (await online(page)).text);
		await sleep(100);
	}
	await sleep(1500); // it lands
	const landed = await online(page);
	report.samples.push(landed.text);
	// Under a second : two seconds of Right walk the hero off the end of the world
	await page.keyboard.down('ArrowRight');
	let farthest = landed.x;
	for (let i = 0; i < 8; i++) {
		await sleep(100);
		const now = await online(page);
		report.samples.push(now.text);
		if (now.x !== null) farthest = Math.max(farthest, now.x);
		if (i === 5) await page.screenshot({ path: `${out}/tab_3_run.png` });
	}
	await page.keyboard.up('ArrowRight');
	await sleep(1000);
	const walked = await online(page);
	report.samples.push(walked.text);
	if (walked.x !== null) farthest = Math.max(farthest, walked.x);
	report.player = walked.player;
	report.snapshots = walked.snapshots;
	report.fromX = landed.x;
	report.farthestX = farthest;
	say(`player ${walked.player}, ${walked.snapshots} snapshots, x ${landed.x} -> ${farthest}`);

	if (walked.state !== 'IN') fail(`the session is ${walked.state}`);
	if (walked.snapshots < 40) fail(`${walked.snapshots} snapshots drawn`);
	if (!(farthest - landed.x > 0.5)) fail(`the hero did not walk right in the tab: ${landed.x} -> ${farthest}`);
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
