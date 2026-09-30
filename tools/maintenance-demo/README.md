# Historical maintenance component consumer

This PowerShell consumer invokes the exact Java investigation core from a historical
reference-assembly handoff and writes a new result, Markdown report, and run-info file.
The historical handoff is not bundled in this public source repository.

## Inputs and prerequisites

- PowerShell 7.2+ on Windows and Docker Desktop with its Linux engine.
- The separately supplied, matching handoff snapshot and its exact source/index locks.
- The pinned local image and dependency JARs accepted by the launcher.
- An existing parent for a new output directory.

The launcher does not pull an image, install dependencies, run Java on the host, start
the Factory, or query current certification authority. Its result is a historical
snapshot consumer run, not a new Factory inspection, certification, or shipment.

After preparing those inputs, run from the repository root:

```powershell
.\run-demo.ps1
.\run-demo.ps1 -InputPath .\tools\maintenance-demo\examples\incident.json
```

[The synthetic example](examples/incident.json) uses the component's incident/evidence
contract. Output goes to a new consumer directory, never inside the handoff. It includes
`result.json`, `report.md`, and `run-info.json`. Existing output is not overwritten.

The component classifies supplied observations using deterministic rules. It does
not perform LLM root-cause analysis or automatically repair external services.

## Standalone checks

```powershell
.\tools\maintenance-demo\Test-MaintenanceDemo.ps1
.\tools\maintenance-demo\Test-MaintenanceDemo.ps1 -Docker
```

The Docker path also needs the exact historical inputs. These consumer checks are
separate from Factory reactor checks and product certification.
See [setup](../../docs/local-setup.md) and [validation](../../docs/validation.md).
