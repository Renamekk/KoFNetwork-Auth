'use strict';

/*
 * Личный кабинет KoF Network.
 *
 * Токены держим в sessionStorage, а не в localStorage: вкладка закрыта — доступ
 * закончился. На общем компьютере это существенная разница, а неудобство
 * невелико, потому что refresh-токен всё равно живёт до конца сессии вкладки.
 *
 * DOM собираем через textContent и createElement, а не innerHTML: ник, город и
 * user-agent приходят из внешнего мира, и вставка их разметкой — прямой путь
 * к XSS в собственном кабинете.
 */

const store = {
    get access() { return sessionStorage.getItem('access'); },
    get refresh() { return sessionStorage.getItem('refresh'); },
    save(pair) {
        sessionStorage.setItem('access', pair.accessToken);
        sessionStorage.setItem('refresh', pair.refreshToken);
    },
    clear() { sessionStorage.clear(); }
};

const $ = (id) => document.getElementById(id);

// ------------------------------------------------------------------ сеть

/** Запрос к API с однократной попыткой обновить протухший access-токен. */
async function api(path, options = {}, retry = true) {
    const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) };
    if (store.access) {
        headers['Authorization'] = 'Bearer ' + store.access;
    }

    const response = await fetch('/api' + path, { ...options, headers });

    if (response.status === 401 && retry && store.refresh) {
        // Access живёт 15 минут; обновляем и повторяем ровно один раз, иначе
        // при отозванной сессии получился бы бесконечный цикл.
        const refreshed = await fetch('/api/auth/refresh', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ refreshToken: store.refresh })
        });
        if (refreshed.ok) {
            store.save(await refreshed.json());
            return api(path, options, false);
        }
        store.clear();
        showLogin();
        throw new Error('Сессия истекла');
    }

    const text = await response.text();
    const body = text ? JSON.parse(text) : null;

    if (!response.ok) {
        const error = new Error((body && body.message) || 'Ошибка запроса');
        // Код нужен вызывающему там, где на разные отказы реакция разная:
        // например, исчерпанная CAPTCHA (410) требует новой задачи, а неверный
        // ответ (400) — просто повторной попытки.
        error.status = response.status;
        error.body = body;
        throw error;
    }
    return body;
}

const post = (path, data) => api(path, { method: 'POST', body: JSON.stringify(data || {}) });
const del = (path) => api(path, { method: 'DELETE' });

// ------------------------------------------------------------------ интерфейс

let toastTimer;

function toast(message, kind = '') {
    const node = $('toast');
    node.textContent = message;
    node.className = 'toast ' + kind;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => node.classList.add('hidden'), 4000);
}

function show(view) {
    ['view-login', 'view-register', 'view-forgot', 'view-account']
        .forEach((id) => $(id).classList.toggle('hidden', id !== view));
    $('logout').classList.toggle('hidden', view !== 'view-account');
}

const showLogin = () => show('view-login');

function formData(form) {
    return Object.fromEntries(new FormData(form).entries());
}

/** Создаёт элемент с текстом. Текст выставляется свойством, не разметкой. */
function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
}

function fillList(container, items, render) {
    container.replaceChildren();
    if (!items || items.length === 0) {
        container.append(el('li', 'empty', 'Записей нет'));
        return;
    }
    items.forEach((item) => container.append(render(item)));
}

const formatTime = (iso) => new Date(iso).toLocaleString('ru-RU', {
    day: '2-digit', month: '2-digit', year: 'numeric',
    hour: '2-digit', minute: '2-digit'
});

// ------------------------------------------------------------------ вход

$('form-login').addEventListener('submit', async (event) => {
    event.preventDefault();
    const data = formData(event.target);
    try {
        const result = await post('/auth/login', data);
        if (result.status === 'two_factor_required') {
            $('field-totp').classList.remove('hidden');
            toast('Введите код подтверждения (' + result.method + ')');
            return;
        }
        store.save(result);
        await openAccount();
    } catch (e) {
        toast(e.message, 'err');
    }
});

// ------------------------------------------------------------------ CAPTCHA

// Текущая задача. null — сервер CAPTCHA не требует либо она уже пройдена.
let captcha = null;

/**
 * Запрашивает задачу и раскладывает её по форме.
 *
 * <p>Ответ сервер не присылает: для кнопок мы отправляем номер нажатой,
 * для картинки — введённый код, и правильность знает только сервер.
 */
async function loadCaptcha() {
    const block = $('captcha-block');
    try {
        captcha = await post('/captcha');
    } catch {
        // CAPTCHA выключена или недоступна — регистрация не должна из-за этого
        // становиться невозможной. Сервер всё равно проверит своё условие.
        captcha = null;
        block.classList.add('hidden');
        return;
    }

    $('captcha-prompt').textContent = captcha.prompt;
    block.classList.remove('hidden');

    const image = $('captcha-image');
    const field = $('captcha-field');
    const options = $('captcha-options');

    options.replaceChildren();
    $('captcha-answer').value = '';

    if (captcha.imageBase64) {
        image.src = 'data:image/png;base64,' + captcha.imageBase64;
        image.classList.remove('hidden');
        field.classList.remove('hidden');
    } else {
        image.classList.add('hidden');
        field.classList.add('hidden');
    }

    // Номер варианта — это и есть ответ, поэтому кнопки нумеруются с единицы
    // в том порядке, в котором их прислал сервер.
    (captcha.options || []).forEach((label, index) => {
        const button = el('button', 'captcha-option', label);
        button.type = 'button';
        button.dataset.answer = String(index + 1);
        button.addEventListener('click', () => selectCaptcha(button.dataset.answer));
        options.append(button);
    });
}

/** Отмечает выбранный вариант; проверка произойдёт при отправке формы. */
function selectCaptcha(answer) {
    $('captcha-options').querySelectorAll('.captcha-option')
        .forEach((b) => b.classList.toggle('chosen', b.dataset.answer === answer));
    $('captcha-answer').value = answer;
}

/**
 * Проверяет ответ.
 *
 * @returns {Promise<boolean>} пройдена ли задача
 */
async function verifyCaptcha() {
    if (!captcha) {
        return true;
    }
    const answer = $('captcha-answer').value.trim();
    if (!answer) {
        toast('Решите задачу', 'err');
        return false;
    }
    try {
        await post('/captcha/verify', { challengeId: captcha.challengeId, answer });
        captcha = null;
        $('captcha-block').classList.add('hidden');
        return true;
    } catch (e) {
        // 410 означает исчерпанные попытки: старая задача больше не примет ответ,
        // и продолжать её показывать было бы тупиком.
        toast(e.status === 410 ? 'Попытки исчерпаны, вот новая задача' : 'Неверный ответ', 'err');
        await loadCaptcha();
        return false;
    }
}

$('captcha-reload').addEventListener('click', (e) => { e.preventDefault(); loadCaptcha(); });

$('form-register').addEventListener('submit', async (event) => {
    event.preventDefault();
    // Порядок существенен: пароль уходит на сервер только после решённой задачи.
    if (!await verifyCaptcha()) {
        return;
    }
    try {
        store.save(await post('/auth/register', formData(event.target)));
        await openAccount();
    } catch (e) {
        toast(e.message, 'err');
        // Задача одноразовая: после любой неудачи регистрации нужна новая.
        await loadCaptcha();
    }
});

$('form-forgot').addEventListener('submit', async (event) => {
    event.preventDefault();
    await post('/auth/forgot-password', formData(event.target));
    // Ответ одинаков и для существующего ника, и для несуществующего —
    // сообщение здесь тоже нейтральное.
    toast('Если аккаунт существует и к нему привязана подтверждённая почта, код отправлен');
    $('form-reset').classList.remove('hidden');
});

$('form-reset').addEventListener('submit', async (event) => {
    event.preventDefault();
    try {
        await post('/auth/reset-password', formData(event.target));
        toast('Пароль изменён, войдите заново', 'ok');
        showLogin();
    } catch (e) {
        toast(e.message, 'err');
    }
});

$('to-register').addEventListener('click', (e) => {
    e.preventDefault();
    show('view-register');
    loadCaptcha();
});
$('to-login').addEventListener('click', (e) => { e.preventDefault(); showLogin(); });
$('to-login-2').addEventListener('click', (e) => { e.preventDefault(); showLogin(); });
$('to-forgot').addEventListener('click', (e) => { e.preventDefault(); show('view-forgot'); });

$('logout').addEventListener('click', async () => {
    try { await post('/auth/logout'); } catch { /* сессия могла уже истечь */ }
    store.clear();
    showLogin();
});

// ------------------------------------------------------------------ кабинет

async function openAccount() {
    show('view-account');
    await Promise.all([loadProfile(), loadSessions(), loadDevices(), loadHistory(), loadSecurity()]);
}

async function loadProfile() {
    const profile = await api('/profile');
    $('account-name').textContent = profile.username;

    const score = securityScore(profile);
    $('score-fill').style.width = score + '%';
    $('score-text').textContent = 'Защищённость ' + score + '%';

    const facts = $('account-facts');
    facts.replaceChildren();
    const add = (label, value) => {
        facts.append(el('dt', null, label), el('dd', null, value));
    };
    add('Регистрация', formatTime(profile.registrationDate));
    add('Последний вход', profile.lastLoginAt ? formatTime(profile.lastLoginAt) : 'никогда');
    add('Адрес', profile.lastLoginIpMasked || '—');
    add('Расположение', [profile.lastCountry, profile.lastCity].filter(Boolean).join(', ') || '—');
    add('Роли', (profile.roles || []).join(', ') || '—');
    add('Статус', profile.status);
}

/** Повторяет формулу из AccountProfileDto: сервер её тоже считает. */
function securityScore(profile) {
    let score = 20;
    if (profile.emailVerified) score += 20; else if (profile.emailLinked) score += 5;
    if (profile.totpEnabled) score += 35;
    if (profile.telegramLinked) score += 15;
    if (profile.discordLinked) score += 10;
    return Math.min(100, score);
}

async function loadSessions() {
    const sessions = await api('/sessions');
    fillList($('list-sessions'), sessions, (session) => {
        const item = el('li');
        const left = el('div');
        left.append(el('div', null, session.type + (session.current ? ' — это устройство' : '')));
        left.append(el('div', 'meta', [
            session.ipMasked,
            [session.country, session.city].filter(Boolean).join(', '),
            formatTime(session.lastSeenAt)
        ].filter(Boolean).join(' · ')));
        item.append(left);

        if (!session.current) {
            const button = el('button', 'ghost', 'Завершить');
            button.addEventListener('click', async () => {
                await del('/sessions/' + encodeURIComponent(session.publicId));
                toast('Сессия завершена', 'ok');
                await loadSessions();
            });
            item.append(button);
        } else {
            item.append(el('span', 'tag ok', 'текущая'));
        }
        return item;
    });
}

$('revoke-all').addEventListener('click', async () => {
    const result = await post('/sessions/revoke-all');
    toast('Завершено сессий: ' + result.revoked, 'ok');
    await loadSessions();
});

async function loadDevices() {
    const devices = await api('/devices');
    fillList($('list-devices'), devices, (device) => {
        const item = el('li');
        const left = el('div');
        left.append(el('div', null, device.name));
        left.append(el('div', 'meta',
            device.lastSeenIpMasked + ' · ' + formatTime(device.lastSeenAt)));
        item.append(left);
        if (device.blocked) item.append(el('span', 'tag err', 'заблокировано'));
        else if (device.trusted) item.append(el('span', 'tag ok', 'доверенное'));
        return item;
    });
}

async function loadHistory() {
    const history = await api('/history?limit=50');
    fillList($('list-history'), history, (entry) => {
        const item = el('li');
        const left = el('div');
        left.append(el('div', null, entry.success ? 'Успешный вход' : 'Неудачная попытка'));
        left.append(el('div', 'meta', [
            entry.ipMasked,
            [entry.country, entry.city].filter(Boolean).join(', '),
            entry.source,
            formatTime(entry.at)
        ].filter(Boolean).join(' · ')));
        item.append(left);
        item.append(el('span', 'tag ' + (entry.success ? 'ok' : 'err'),
            entry.success ? 'ок' : entry.result));
        return item;
    });
}

// ------------------------------------------------------------------ безопасность

async function loadSecurity() {
    const profile = await api('/profile');
    renderEmail(profile);
    await renderTotp();
    renderLinks(profile);
}

function renderEmail(profile) {
    const block = $('email-block');
    block.replaceChildren();

    if (!profile.emailLinked) {
        const form = el('form');
        const label = el('label', null, 'Адрес почты');
        const input = el('input');
        input.name = 'email';
        input.type = 'email';
        input.required = true;
        label.append(input);
        form.append(label, el('button', null, 'Привязать'));
        form.addEventListener('submit', async (event) => {
            event.preventDefault();
            try {
                await post('/email/link', { email: input.value });
                toast('Код отправлен на указанный адрес', 'ok');
                await loadSecurity();
            } catch (e) { toast(e.message, 'err'); }
        });
        block.append(form);
        return;
    }

    const row = el('div', 'row-between');
    row.append(el('span', null, profile.emailVerified
        ? 'Почта подтверждена'
        : 'Почта привязана, но не подтверждена'));
    row.append(el('span', 'tag ' + (profile.emailVerified ? 'ok' : 'warn'),
        profile.emailVerified ? 'ок' : 'ждёт кода'));
    block.append(row);

    if (!profile.emailVerified) {
        const form = el('form');
        const label = el('label', null, 'Код из письма');
        const input = el('input');
        input.name = 'code';
        input.required = true;
        label.append(input);
        form.append(label, el('button', null, 'Подтвердить'));
        form.addEventListener('submit', async (event) => {
            event.preventDefault();
            try {
                await post('/email/verify', { code: input.value });
                toast('Почта подтверждена', 'ok');
                await loadSecurity();
            } catch (e) { toast(e.message, 'err'); }
        });
        block.append(form);
    }

    const unlink = el('button', 'ghost', 'Отвязать почту');
    unlink.addEventListener('click', async () => {
        await del('/email');
        toast('Почта отвязана');
        await loadSecurity();
    });
    block.append(unlink);
}

async function renderTotp() {
    const block = $('totp-block');
    block.replaceChildren();
    const status = await api('/totp/status');

    if (status.enabled) {
        block.append(el('div', null,
            'Включена. Резервных кодов осталось: ' + status.recoveryCodesLeft));

        const form = el('form');
        const label = el('label', null, 'Код из приложения для отключения');
        const input = el('input');
        input.name = 'code';
        input.required = true;
        label.append(input);
        const button = el('button', 'danger', 'Отключить');
        form.append(label, button);
        form.addEventListener('submit', async (event) => {
            event.preventDefault();
            try {
                await post('/totp/disable', { code: input.value });
                toast('Второй фактор отключён');
                await loadSecurity();
            } catch (e) { toast(e.message, 'err'); }
        });
        block.append(form);
        return;
    }

    const start = el('button', null, 'Подключить');
    start.addEventListener('click', async () => {
        const setup = await post('/totp/setup');
        block.replaceChildren();

        if (setup.qrCodePngBase64) {
            const image = el('img', 'qr');
            image.src = 'data:image/png;base64,' + setup.qrCodePngBase64;
            image.alt = 'QR-код для приложения';
            block.append(image);
        }
        block.append(el('div', 'meta', 'Или введите секрет вручную: ' + setup.secret));

        block.append(el('div', null, 'Резервные коды — сохраните их сейчас, '
            + 'второй раз они не покажутся:'));
        const codes = el('div', 'codes');
        setup.recoveryCodes.forEach((code) => codes.append(el('span', null, code)));
        block.append(codes);

        const form = el('form');
        const label = el('label', null, 'Код из приложения');
        const input = el('input');
        input.name = 'code';
        input.required = true;
        input.inputMode = 'numeric';
        label.append(input);
        form.append(label, el('button', null, 'Подтвердить'));
        form.addEventListener('submit', async (event) => {
            event.preventDefault();
            try {
                await post('/totp/confirm', { code: input.value });
                toast('Второй фактор включён', 'ok');
                await loadSecurity();
            } catch (e) { toast(e.message, 'err'); }
        });
        block.append(form);
    });
    block.append(el('div', null, 'Не подключена'), start);
}

function renderLinks(profile) {
    const block = $('link-block');
    block.replaceChildren();

    [['Telegram', 'telegram', profile.telegramLinked],
     ['Discord', 'discord', profile.discordLinked]].forEach(([title, key, linked]) => {
        const row = el('div', 'row-between');
        row.append(el('span', null, title + (linked ? ' — привязан' : ' — не привязан')));

        const button = el('button', linked ? 'ghost' : '', linked ? 'Отвязать' : 'Получить код');
        button.addEventListener('click', async () => {
            try {
                if (linked) {
                    await del('/' + key);
                    toast(title + ' отвязан');
                } else {
                    const result = await post('/' + key + '/link');
                    toast('Код: ' + result.code + ' — введите его боту командой /link', 'ok');
                }
                await loadSecurity();
            } catch (e) { toast(e.message, 'err'); }
        });
        row.append(button);

        // Discord умеет ещё и OAuth2: тогда код переписывать не нужно. Кнопка
        // появляется, только если поток настроен на сервере, — иначе она вела бы
        // в ошибку, и пользователю пришлось бы догадываться, что делать дальше.
        if (key === 'discord' && !linked) {
            const oauth = el('button', 'ghost', 'Через Discord');
            oauth.addEventListener('click', async () => {
                try {
                    const result = await post('/discord/oauth/start');
                    // Уход со страницы, а не новое окно: возврат от Discord —
                    // это обычный редирект, и он должен попасть в ту же вкладку.
                    window.location.href = result.url;
                } catch (e) { toast(e.message, 'err'); }
            });
            row.append(oauth);
        }
        block.append(row);
    });
}

/**
 * Показывает итог возврата из Discord.
 *
 * <p>Параметр убирается из адреса сразу: иначе обновление страницы повторит
 * сообщение о привязке, которой уже не происходит.
 */
function reportDiscordOutcome() {
    const outcome = new URLSearchParams(window.location.search).get('discord');
    if (!outcome) {
        return;
    }
    window.history.replaceState({}, '', window.location.pathname);

    const messages = {
        linked: ['Discord привязан', 'ok'],
        denied: ['Привязка отменена', ''],
        expired: ['Ссылка устарела, начните заново', 'err'],
        bad_request: ['Discord вернул неполный ответ', 'err'],
        exchange_failed: ['Не удалось связаться с Discord', 'err'],
        discord_already_linked: ['Этот Discord уже привязан к другому аккаунту', 'err'],
        already_linked: ['К аккаунту уже привязан Discord', 'err']
    };
    const [message, kind] = messages[outcome] || ['Не удалось привязать Discord', 'err'];
    toast(message, kind);
}

reportDiscordOutcome();

$('form-password').addEventListener('submit', async (event) => {
    event.preventDefault();
    try {
        await post('/profile/password', formData(event.target));
        toast('Пароль изменён. Остальные сессии завершены.', 'ok');
        event.target.reset();
        await loadSessions();
    } catch (e) {
        toast(e.message, 'err');
    }
});

// ------------------------------------------------------------------ вкладки

document.querySelectorAll('.tabs button').forEach((button) => {
    button.addEventListener('click', () => {
        document.querySelectorAll('.tabs button')
            .forEach((other) => other.classList.toggle('active', other === button));
        ['sessions', 'devices', 'history', 'security'].forEach((name) => {
            $('tab-' + name).classList.toggle('hidden', name !== button.dataset.tab);
        });
    });
});

// ------------------------------------------------------------------ старт

(async () => {
    if (!store.access) {
        showLogin();
        return;
    }
    try {
        await openAccount();
    } catch {
        store.clear();
        showLogin();
    }
})();
