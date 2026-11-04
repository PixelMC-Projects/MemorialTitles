// Local source preview with a labelled isolated HTTP fixture; no production/server/client access.
import { createRequire } from 'node:module';
import { mkdir, writeFile } from 'node:fs/promises';
import { spawn } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import assert from 'node:assert/strict';
const require = createRequire(import.meta.url);
const { chromium } = require('C:/Users/Administrator/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const base = 'http://127.0.0.1:18829';
const checks = [], errors = [];
const check = (value, label) => { assert.ok(value, label); checks.push(label); };
const original = { id:'local-fixture', name:{segments:[{text:'山海同行者',color:0xffffff,bold:true,italic:false,underline:false,strike:false}],gradients:[]},description:'本地浏览器自动化样例，仅验证编辑控件。',source:'本地样例',acquisition:'仅作获取说明',category:'纪念',order:0,icon:777,visible:true,issuable:true,revision:1,createdAt:1 };
let title = structuredClone(original), saves = [];
const out = new URL('../screenshots/player-hub-20261104/', import.meta.url);
await mkdir(out, { recursive:true });
const server = spawn(process.execPath, ['node_modules/vite/bin/vite.js','--host','127.0.0.1','--port','18829','--strictPort'], { cwd:new URL('../',import.meta.url),windowsHide:true,stdio:'ignore' });
let browser;
try {
  for(let i=0;i<100;i++){try{if((await fetch(base)).ok)break;}catch{}await delay(100);if(i===99)throw new Error('Local preview did not start');}
  browser = await chromium.launch({ headless:true, executablePath:'C:/Users/Administrator/AppData/Local/ms-playwright/chromium_headless_shell-1223/chrome-headless-shell-win64/chrome-headless-shell.exe' });
  const context = await browser.newContext({viewport:{width:1280,height:1000}});
  const page = await context.newPage();
  page.on('pageerror', error=>errors.push(error.message));
  await page.route('**/api/**',async route=>{
    const request=route.request(), path=new URL(request.url()).pathname;
    let value;
    if(path==='/api/me')value={username:'local-fixture',playerName:'Local fixture',csrf:'isolated-browser-fixture'};
    else if(path==='/api/state')value={titles:[title],holderCounts:{},settings:{head:true,chat:true,tab:true,headScale:1,headOffset:.3,chatPrefix:'[{title}] '},health:{database:true,version:'local-fixture'},stamp:'fixture',identities:{}};
    else if(path==='/api/titles'){const body=request.postDataJSON();saves.push(body);title={...body.title,revision:title.revision+1};value={title};}
    else if(path==='/api/title-holders')value={rows:[],total:0,page:0,size:20};
    else value={error:'No local fixture for '+path};
    await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(value)});
  });
  await page.goto(base);await page.getByRole('button',{name:'查看称号详情 山海同行者'}).click();
  const dialog=page.getByRole('dialog');
  await page.getByRole('combobox',{name:'卡片徽章',exact:true}).click();await page.getByRole('option',{name:'个人专属',exact:true}).click();
  await page.getByRole('combobox',{name:'稀有度',exact:true}).click();await page.getByRole('option',{name:'珍藏',exact:true}).click();
  await page.getByRole('combobox',{name:'卡片主题',exact:true}).click();await page.getByRole('option',{name:'柔玉',exact:true}).click();
  check(await page.getByLabel('玩家称号卡片预览').getByText('珍藏',{exact:true}).isVisible(),'rarity immediately updates local card preview');
  check(await page.getByRole('combobox',{name:'卡片徽章',exact:true}).innerText().then(x=>x.includes('个人')),'personal badge is a named visual preset');
  await page.getByRole('tab',{name:'持有名单',exact:true}).click();await page.getByRole('tab',{name:'基本资料',exact:true}).click();
  check(await page.getByRole('combobox',{name:'卡片主题',exact:true}).innerText().then(x=>x.includes('柔玉')),'holder tab switching preserves unsaved presentation draft');
  await dialog.locator('.title-presentation-fields').scrollIntoViewIfNeeded();
  await page.screenshot({path:new URL('dark-editor.png',out).pathname.slice(1),fullPage:true});
  check(await page.getByRole('combobox',{name:'卡片主题',exact:true}).locator('.title-theme-swatch').count()===1,'selected theme shows a color swatch beside its accessible name');
  await page.getByRole('combobox',{name:'卡片主题',exact:true}).click();
  check(await page.getByRole('listbox',{name:'卡片主题选项',exact:true}).locator('.title-theme-swatch').count()===14,'all14 named theme options show restrained color swatches');
  await page.screenshot({path:new URL('dark-theme-options.png',out).pathname.slice(1),fullPage:true});await page.keyboard.press('Escape');
  await page.getByRole('button',{name:'保存并同步',exact:true}).click();await dialog.waitFor({state:'hidden'});
  check(saves.length===1&&saves[0].title.icon===777&&saves[0].title.presentation.badge==='personal'&&saves[0].title.presentation.rarity==='treasured'&&saves[0].title.presentation.theme==='jade','save sends separate presentation and unchanged CustomModelData');
  check(JSON.stringify(saves[0].title.name)===JSON.stringify(original.name),'presentation edits preserve rich text exactly');
  await page.getByRole('button',{name:'关闭提示',exact:true}).click();
  await page.getByRole('button',{name:'切换浅色外观',exact:true}).click();
  await page.getByRole('button',{name:'查看称号详情 山海同行者'}).click();
  check(await page.getByRole('combobox',{name:'卡片主题',exact:true}).innerText().then(x=>x.includes('柔玉')),'reopening saved title restores selected metadata');
  await dialog.locator('.title-presentation-fields').scrollIntoViewIfNeeded();await page.screenshot({path:new URL('light-editor.png',out).pathname.slice(1),fullPage:true});
  await page.getByRole('combobox',{name:'卡片主题',exact:true}).click();await page.screenshot({path:new URL('light-theme-options.png',out).pathname.slice(1),fullPage:true});await page.keyboard.press('Escape');
  await page.getByRole('combobox',{name:'卡片主题',exact:true}).focus();await page.keyboard.press('ArrowDown');await page.keyboard.press('End');await page.keyboard.press('Enter');
  check(await page.getByRole('combobox',{name:'卡片主题',exact:true}).innerText().then(x=>x.includes('岩灰')),'custom themed preset supports keyboard selection');
  await page.setViewportSize({width:390,height:844});await dialog.locator('.title-presentation-fields').scrollIntoViewIfNeeded();
  await page.screenshot({path:new URL('light-narrow-editor.png',out).pathname.slice(1),fullPage:true});
  const overflow=await dialog.evaluate(node=>node.scrollWidth>node.clientWidth+1);
  check(!overflow,'narrow editor keeps existing width with no horizontal overflow');
  check(errors.length===0,'local preview has no browser page errors');
  await writeFile(new URL('report.json',out),JSON.stringify({passed:true,kind:'local mocked HTTP browser fixture; not gameplay or production acceptance',checks,errors},null,2));
  console.log(JSON.stringify({passed:true,checks:checks.length,output:out.pathname}));
} finally {
  if(browser)await browser.close();
  server.kill();
}
