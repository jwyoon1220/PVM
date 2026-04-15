# PVM x86 Decoder (State + Chain of Responsibility)

이 프로젝트의 디코더는 다음 두 패턴을 결합합니다.

- **State Pattern**: Prefix -> Opcode -> ModR/M -> SIB -> Displacement -> Immediate -> Execute
- **Chain of Responsibility**: Opcode를 핸들러 체인으로 해석 (`NopHandler -> MovR32Rm32Handler -> ...`)

핵심 구현 위치:

- `src/main/kotlin/io/github/jwyoon1220/pvm/hardware/Decoder.kt`
- `src/main/kotlin/io/github/jwyoon1220/pvm/hardware/decode/DecoderPipeline.kt`
- `src/test/kotlin/io/github/jwyoon1220/pvm/hardware/DecoderStateFlowTest.kt`

## Flow Example

명령어: `MOV EAX, [EBX+ECX*4+0x10]`

인코딩: `8B 44 8B 10`

상태 전이(trace):

1. `PrefixState`
2. `OpcodeState(opcode=8B, mnemonic=MOV r32, r/m32)`
3. `ModRmState(modRM=44)`
4. `SibState(sib=8B)`
5. `DisplacementState(displacement=16)`
6. `ExecuteState(MOV r32, r/m32)`

## OCP Extension

새 명령어를 추가할 때 기존 상태 코드를 수정하지 않고:

1. 새 `OpcodeHandlerLink` 구현 추가
2. 새 `InstructionExecutor` 구현 추가
3. `DecoderPipeline(opcodeHandler = yourChain)`로 주입

즉, 디코딩 단계(State)와 명령어 의미(Op handler/Executor)를 분리해 확장 시 변경 영향을 줄입니다.

