# -*- coding: utf-8 -*-
"""
Telegram-бот «Генератор идей» с интеграцией GigaChat API.

Команды:
  /start  — приветствие и инструкция
  /survey — опрос по интересам (FSM)
  /ideas  — генерация персонализированных идей через GigaChat
  /reset  — сброс контекста пользователя
"""

import asyncio
import logging
import os
from typing import Dict, List

from aiogram import Bot, Dispatcher, Router
from aiogram.filters import Command, CommandStart
from aiogram.fsm.context import FSMContext
from aiogram.fsm.state import State, StatesGroup
from aiogram.types import Message
from dotenv import load_dotenv
from gigachat import GigaChat
from gigachat.models import Chat, Messages, MessagesRole

# ---------- Загрузка переменных окружения ----------
load_dotenv()

TELEGRAM_BOT_TOKEN = os.getenv("TELEGRAM_BOT_TOKEN", "")
GIGACHAT_CREDENTIALS = os.getenv("GIGACHAT_CREDENTIALS", "")
GIGACHAT_SCOPE = os.getenv("GIGACHAT_SCOPE", "GIGACHAT_API_PERS")

if not TELEGRAM_BOT_TOKEN:
    raise ValueError("Не задан TELEGRAM_BOT_TOKEN. Добавьте его в файл .env")
if not GIGACHAT_CREDENTIALS:
    raise ValueError("Не задан GIGACHAT_CREDENTIALS. Добавьте его в файл .env")

# ---------- Логирование ----------
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger(__name__)

# ---------- Роутер ----------
router = Router()

# ---------- Хранилище данных пользователей ----------
# Ключ — user_id, значение — словарь с профилем и историей диалога
user_data: Dict[int, Dict] = {}

# Максимальное количество сообщений в истории диалога
MAX_HISTORY = 10


def get_user(user_id: int) -> Dict:
    """Получить или создать профиль пользователя."""
    if user_id not in user_data:
        user_data[user_id] = {
            "profile": {},      # ответы из опроса
            "history": [],      # история диалога с GigaChat
        }
    return user_data[user_id]


# ---------- FSM: состояния опроса ----------
class SurveyStates(StatesGroup):
    hobby = State()
    work = State()
    tasks = State()
    topics = State()


# ---------- /start ----------
@router.message(CommandStart())
async def cmd_start(message: Message) -> None:
    """Приветствие и краткая инструкция."""
    text = (
        "Привет! Я — <b>Генератор идей</b> 💡\n\n"
        "Я помогу придумать интересные идеи, основываясь на твоих интересах.\n\n"
        "<b>Команды:</b>\n"
        "/survey — пройти опрос по интересам\n"
        "/ideas — сгенерировать 5 персонализированных идей\n"
        "/reset — сбросить контекст и начать заново\n\n"
        "Начни с команды /survey, чтобы я узнал тебя лучше!"
    )
    await message.answer(text, parse_mode="HTML")


# ---------- /survey ----------
@router.message(Command("survey"))
async def cmd_survey(message: Message, state: FSMContext) -> None:
    """Начало опроса — первый вопрос."""
    await state.set_state(SurveyStates.hobby)
    await message.answer(
        "📋 <b>Опрос по интересам</b>\n\n"
        "Вопрос 1/4: Какие у тебя хобби и увлечения?",
        parse_mode="HTML",
    )


@router.message(SurveyStates.hobby)
async def survey_hobby(message: Message, state: FSMContext) -> None:
    """Сохранить хобби, перейти к следующему вопросу."""
    user = get_user(message.from_user.id)
    user["profile"]["hobby"] = message.text
    await state.set_state(SurveyStates.work)
    await message.answer("Вопрос 2/4: Чем ты занимаешься по работе / учёбе?")


@router.message(SurveyStates.work)
async def survey_work(message: Message, state: FSMContext) -> None:
    """Сохранить информацию о работе."""
    user = get_user(message.from_user.id)
    user["profile"]["work"] = message.text
    await state.set_state(SurveyStates.tasks)
    await message.answer("Вопрос 3/4: Какие задачи сейчас перед тобой стоят?")


@router.message(SurveyStates.tasks)
async def survey_tasks(message: Message, state: FSMContext) -> None:
    """Сохранить текущие задачи."""
    user = get_user(message.from_user.id)
    user["profile"]["tasks"] = message.text
    await state.set_state(SurveyStates.topics)
    await message.answer("Вопрос 4/4: Какие темы тебе особенно интересны?")


@router.message(SurveyStates.topics)
async def survey_topics(message: Message, state: FSMContext) -> None:
    """Сохранить любимые темы, завершить опрос."""
    user = get_user(message.from_user.id)
    user["profile"]["topics"] = message.text
    await state.clear()

    profile = user["profile"]
    await message.answer(
        "✅ Опрос завершён! Вот твой профиль:\n\n"
        f"• <b>Хобби:</b> {profile.get('hobby', '—')}\n"
        f"• <b>Работа/учёба:</b> {profile.get('work', '—')}\n"
        f"• <b>Текущие задачи:</b> {profile.get('tasks', '—')}\n"
        f"• <b>Любимые темы:</b> {profile.get('topics', '—')}\n\n"
        "Теперь используй /ideas для генерации идей!",
        parse_mode="HTML",
    )


# ---------- Работа с GigaChat ----------
def build_system_prompt(profile: Dict) -> str:
    """Сформировать системный промпт на основе профиля пользователя."""
    parts = ["Ты — креативный помощник-генератор идей."]

    if profile:
        parts.append("Информация о пользователе:")
        if profile.get("hobby"):
            parts.append(f"- Хобби: {profile['hobby']}")
        if profile.get("work"):
            parts.append(f"- Работа/учёба: {profile['work']}")
        if profile.get("tasks"):
            parts.append(f"- Текущие задачи: {profile['tasks']}")
        if profile.get("topics"):
            parts.append(f"- Интересные темы: {profile['topics']}")

    parts.append(
        "Учитывай интересы и контекст пользователя при генерации идей. "
        "Отвечай на русском языке."
    )
    return "\n".join(parts)


def trim_history(history: List[Dict], max_len: int = MAX_HISTORY) -> List[Dict]:
    """Обрезать историю диалога до max_len последних сообщений."""
    if len(history) > max_len:
        return history[-max_len:]
    return history


def generate_ideas(profile: Dict, history: List[Dict]) -> str:
    """Сгенерировать идеи через GigaChat в потоковом режиме."""
    system_prompt = build_system_prompt(profile)

    messages = [
        Messages(role=MessagesRole.SYSTEM, content=system_prompt),
    ]

    # Добавить историю диалога
    for msg in history:
        messages.append(
            Messages(role=msg["role"], content=msg["content"])
        )

    # Запрос на генерацию идей
    user_request = (
        "Придумай 5 оригинальных и персонализированных идей для меня. "
        "Каждую идею опиши кратко (2-3 предложения) и пронумеруй."
    )
    messages.append(Messages(role=MessagesRole.USER, content=user_request))

    # Потоковая генерация через GigaChat
    with GigaChat(
        credentials=GIGACHAT_CREDENTIALS,
        scope=GIGACHAT_SCOPE,
        model="GigaChat-2-Lite",
        verify_ssl_certs=False,
        profanity_check=False,
    ) as giga:
        result_parts: list[str] = []
        payload = Chat(
            model="GigaChat-2-Lite",
            messages=messages,
            stream=True,
        )
        for chunk in giga.stream(payload):
            content = chunk.choices[0].delta.content
            if content:
                result_parts.append(content)

        return "".join(result_parts)


# ---------- /ideas ----------
@router.message(Command("ideas"))
async def cmd_ideas(message: Message) -> None:
    """Генерация идей через GigaChat."""
    user = get_user(message.from_user.id)
    profile = user["profile"]

    if not profile:
        await message.answer(
            "Сначала пройди опрос командой /survey, "
            "чтобы я мог генерировать персонализированные идеи!"
        )
        return

    wait_msg = await message.answer("⏳ Генерирую идеи, подожди немного...")

    try:
        result = await asyncio.to_thread(generate_ideas, profile, user["history"])

        # Сохранить в историю
        user["history"].append({"role": "user", "content": "Сгенерируй 5 идей"})
        user["history"].append({"role": "assistant", "content": result})
        user["history"] = trim_history(user["history"])

        await wait_msg.delete()
        await message.answer(
            f"💡 <b>Твои идеи:</b>\n\n{result}",
            parse_mode="HTML",
        )
    except Exception as e:
        logger.error("Ошибка при генерации идей: %s", e)
        await wait_msg.delete()
        await message.answer(
            "❌ Произошла ошибка при обращении к GigaChat. "
            "Попробуй ещё раз позже."
        )


# ---------- /reset ----------
@router.message(Command("reset"))
async def cmd_reset(message: Message, state: FSMContext) -> None:
    """Сброс профиля и истории пользователя."""
    user_id = message.from_user.id
    if user_id in user_data:
        del user_data[user_id]
    await state.clear()
    await message.answer(
        "🔄 Контекст сброшен. Пройди /survey заново, чтобы я узнал тебя лучше!"
    )


# ---------- Запуск бота ----------
async def main() -> None:
    """Точка входа: создание бота и запуск polling."""
    bot = Bot(token=TELEGRAM_BOT_TOKEN)
    dp = Dispatcher()
    dp.include_router(router)

    logger.info("Бот запущен!")
    await dp.start_polling(bot)


if __name__ == "__main__":
    asyncio.run(main())
