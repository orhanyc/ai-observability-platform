interface Snapshot {
    samples: number;
    successRate: number;
    p95Ms: number;
    meanCostMicros: number;
    featureDrift: number;
    predictionDrift: number;
}

interface Incident {
    id: string;
    service: string;
    release: { model: string; prompt: string };
    opened: string;
    reasons: string[];
    evidence: Observation[];
    status: "OPEN" | "ROLLED_BACK";
}

interface Observation {
    traceId: string;
    timestamp: string;
    latencyMs: number;
    costMicros: number;
    success: boolean;
}

const statusLine = document.querySelector<HTMLParagraphElement>("#status")!;
const overview = document.querySelector<HTMLTableSectionElement>("#overview")!;
const incidents = document.querySelector<HTMLDivElement>("#incidents")!;
const refreshButton = document.querySelector<HTMLButtonElement>("#refresh")!;
const number = new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 });
const percent = new Intl.NumberFormat(undefined, { style: "percent", maximumFractionDigits: 1 });

async function request<T>(path: string, method = "GET"): Promise<T> {
    const response = await fetch("/api" + path, { method });
    if (!response.ok) throw new Error("Request failed (" + response.status + ")");
    return await response.json() as T;
}

function row(values: string[]): HTMLTableRowElement {
    const element = document.createElement("tr");
    for (const value of values) {
        const cell = element.insertCell();
        cell.textContent = value;
    }
    return element;
}

function showOverview(snapshots: Record<string, Snapshot>): void {
    overview.replaceChildren();
    for (const [service, sample] of Object.entries(snapshots)) {
        overview.append(row([
            service, String(sample.samples), percent.format(sample.successRate),
            number.format(sample.p95Ms), number.format(sample.meanCostMicros),
            number.format(sample.featureDrift), number.format(sample.predictionDrift),
        ]));
    }
}

function incidentCard(incident: Incident): HTMLElement {
    const card = document.createElement("details");
    const header = document.createElement("summary");
    header.textContent = incident.service + " | " + incident.status + " | "
        + incident.release.model + "/" + incident.release.prompt;
    const reasons = document.createElement("ul");
    for (const reason of incident.reasons) {
        const item = document.createElement("li");
        item.textContent = reason;
        reasons.append(item);
    }
    const evidence = document.createElement("table");
    const headings = evidence.createTHead().insertRow();
    for (const label of ["Trace ID", "Time", "Latency (ms)", "Cost (micro USD)", "Outcome"]) {
        const cell = document.createElement("th");
        cell.scope = "col";
        cell.textContent = label;
        headings.append(cell);
    }
    const body = evidence.createTBody();
    for (const event of incident.evidence) {
        body.append(row([
            event.traceId, new Date(event.timestamp).toLocaleTimeString(),
            number.format(event.latencyMs), number.format(event.costMicros),
            event.success ? "Success" : "Failure",
        ]));
    }
    card.append(header, reasons, evidence);
    if (incident.status === "OPEN") {
        const rollback = document.createElement("button");
        rollback.textContent = "Simulate rollback";
        rollback.addEventListener("click", async () => {
            rollback.disabled = true;
            try {
                await request("/incidents/" + encodeURIComponent(incident.id) + "/rollback", "POST");
                await refresh();
            } catch (error) {
                statusLine.textContent = String(error);
                rollback.disabled = false;
            }
        });
        card.append(rollback);
    }
    return card;
}

async function refresh(): Promise<void> {
    refreshButton.disabled = true;
    try {
        const [snapshots, items] = await Promise.all([
            request<Record<string, Snapshot>>("/overview"),
            request<Incident[]>("/incidents"),
        ]);
        showOverview(snapshots);
        incidents.replaceChildren(...items.map(incidentCard));
        if (items.length === 0) incidents.textContent = "No incidents recorded.";
        statusLine.textContent = "Updated " + new Date().toLocaleTimeString();
    } catch (error) {
        statusLine.textContent = String(error);
    } finally {
        refreshButton.disabled = false;
    }
}

refreshButton.addEventListener("click", refresh);
void refresh();
