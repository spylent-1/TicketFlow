import asyncio
import hmac
import os
import time
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Annotated, Literal
from uuid import UUID

from fastapi import FastAPI, Header, HTTPException
from langfuse import Langfuse
from langgraph.checkpoint.sqlite.aio import AsyncSqliteSaver
from langgraph.types import Command
from pydantic import BaseModel, Field

from .flow import PROMPT_VERSION, Workflow
from .knowledge import Knowledge

KEY = os.environ["TICKETFLOW_INTERNAL_KEY"]


@asynccontextmanager
async def lifespan(app: FastAPI):
    db = Path(os.getenv("TICKETFLOW_CHECKPOINT_PATH", ".local/checkpoints.sqlite"))
    db.parent.mkdir(parents=True, exist_ok=True)
    knowledge = await asyncio.to_thread(Knowledge)
    await asyncio.to_thread(knowledge.index)
    telemetry = Langfuse() if os.getenv("LANGFUSE_PUBLIC_KEY") and os.getenv("LANGFUSE_SECRET_KEY") else None
    workflow = Workflow(knowledge, os.getenv("TICKETFLOW_BUSINESS_URL", "http://127.0.0.1:8080"), KEY, telemetry)
    async with AsyncSqliteSaver.from_conn_string(str(db)) as saver:
        app.state.workflow = workflow
        app.state.graph = workflow.compile(saver)
        # Serialize graph invocations for the SQLite-backed worker.
        app.state.invocations = asyncio.Lock()
        yield
    if telemetry:
        telemetry.flush()


app = FastAPI(title="TicketFlow Agent", lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)


class RunRequest(BaseModel):
    order_id: str = Field(min_length=1, max_length=48)
    description: str = Field(min_length=1, max_length=1200)
    decision: Literal["", "APPROVE", "REJECT"] = ""


@app.get("/health")
def health():
    return {"status": "ok", "model": app.state.workflow.model_name}


@app.post("/runs/{run_id}")
async def run(run_id: UUID, body: RunRequest, x_internal_key: Annotated[str, Header()] = ""):
    if not hmac.compare_digest(KEY.encode(), x_internal_key.encode()):
        raise HTTPException(status_code=403)
    run_id = str(run_id)
    config = {"configurable": {"thread_id": run_id}, "recursion_limit": 24}
    graph = app.state.graph
    workflow = app.state.workflow
    trace_id = Langfuse.create_trace_id(seed=run_id) if workflow.langfuse else None
    async with app.state.invocations:
        snapshot = await graph.aget_state(config)
        if not snapshot.values:
            if body.decision:
                raise HTTPException(status_code=409, detail="Approval checkpoint unavailable")
            graph_input = {"run_id": run_id, "description": body.description,
                           "started": time.time(),
                           "model_name": workflow.model_name, "prompt_version": PROMPT_VERSION}
        elif snapshot.interrupts:
            graph_input = Command(resume=body.decision) if body.decision else None
        else:
            graph_input = None
        should_invoke = graph_input is not None or (snapshot.next and not snapshot.interrupts)
        if should_invoke:
            phase = "approval-resume" if body.decision else "investigation"
            trace_context = {"trace_id": trace_id} if trace_id else None
            metadata = graph_input if isinstance(graph_input, dict) else snapshot.values
            with workflow.span(phase, trace_context=trace_context,
                               metadata={"run_id": run_id,
                                         "prompt_version": metadata.get("prompt_version", PROMPT_VERSION),
                                         "model": metadata.get("model_name", workflow.model_name)}) as span:
                await graph.ainvoke(graph_input, config, durability="sync")
                snapshot = await graph.aget_state(config)
                state = snapshot.values
                if span:
                    span.update(output={"status": state["status"], "reason": state["reason"],
                                        "duration_ms": state["durationMs"], "input_tokens": state.get("inputTokens"),
                                        "output_tokens": state.get("outputTokens")})
        state = snapshot.values
    model = state.get("model_name", workflow.model_name)
    return {name: state[name] for name in ("status", "reason", "citations", "timeline", "durationMs")} | {
        "model": model, "promptVersion": state.get("prompt_version", PROMPT_VERSION), "traceId": trace_id,
        "inputTokens": state.get("inputTokens"),
        "outputTokens": state.get("outputTokens"),
    }
