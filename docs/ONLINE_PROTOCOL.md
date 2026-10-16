# Tic-Tac-Toe online: protocolo y configuración de Firebase

Este documento es el **contrato** entre las dos apps (la tuya y la de tu compañero). Mientras las dos
lean y escriban exactamente esta estructura en la misma base de datos, pueden jugar entre sí aunque el
código de cada una sea distinto.

## 1. Configuración (una sola vez, la hace quien crea el proyecto)

1. En la [consola de Firebase](https://console.firebase.google.com) crea un proyecto.
2. **Authentication → Sign-in method → Anonymous → Habilitar.**
3. **Realtime Database → Crear base de datos** (modo bloqueado). Hazlo *antes* de descargar el
   `google-services.json`, para que incluya la URL de la base.
4. **Realtime Database → Reglas:** pega el contenido de `firebase/database.rules.json` y publica.
5. **Configuración del proyecto → Tus apps → Agregar app Android**, una vez por cada app:
   - tu app: `com.example.tic_tac_toe`
   - la app de tu compañero: su `applicationId`
6. Cada desarrollador descarga **su propio** `google-services.json` y lo pone en `app/` de su proyecto.
   (Si la app de tu compañero ya existe, basta con que el dueño del proyecto lo agregue como app y le
   comparta el archivo, o lo añada como miembro del proyecto.)

Sin `app/google-services.json` este proyecto compila igual, pero la opción **Online** avisa que Firebase
no está configurado.

## 2. Esquema de datos (`schemaVersion` = 1)

Una partida es un nodo `rooms/{code}`, donde `{code}` son **4 dígitos** (`"0000"`–`"9999"`):

```
rooms/4821
  schemaVersion : 1                     número, siempre 1
  createdAt     : 1760000000000         ServerValue.TIMESTAMP (ms)
  players
    X           : "uidDelAnfitrion"     el que crea la sala siempre es X
    O           : "uidDelInvitado"      no existe hasta que alguien se une
  presence
    X           : true                  false si ese jugador se desconectó
    O           : true
  status        : "waiting" | "playing" | "finished"
  board         : "012000000"           texto de 9 caracteres, casillas 0..8 por filas
                                        (izq→der, arriba→abajo): 0 vacía, 1 = X, 2 = O
  turn          : "X" | "O"             a quién le toca
  winner        : "X" | "O" | "draw"    solo existe cuando status = "finished"
  round         : 1                     número de ronda (empieza en 1)
  score
    X           : 0                     victorias acumuladas de X
    O           : 0                     victorias acumuladas de O
```

Convenciones importantes:

- `board` siempre tiene exactamente 9 caracteres `0`, `1` o `2`.
- `uid` es el de **Firebase Authentication anónima** (`FirebaseAuth.currentUser.uid`).
- No existen campos extra: las reglas rechazan cualquier nodo desconocido.

## 3. Flujos

**Crear sala (anfitrión = X)**
1. Iniciar sesión anónima si hace falta.
2. Elegir un código aleatorio de 4 dígitos y escribir la sala con una **transacción** que se aborta si el
   nodo ya existe (y no es más viejo de 6 h). Si falla, probar otro código.
   Valor inicial: `status="waiting"`, `board="000000000"`, `turn="X"`, `round=1`, `score={X:0,O:0}`,
   `players={X: uid}`, `presence={X: true}`.
3. Mostrar el código y escuchar `rooms/{code}` hasta que aparezca `players/O` (`status="playing"`).

**Unirse (invitado = O)**
1. Pedir 4 dígitos. Si no son 4 dígitos → código no válido.
2. **Transacción** sobre `rooms/{code}`: solo se acepta si existe, `status="waiting"`, no hay `players/O`,
   el anfitrión no soy yo y `presence/X` es `true`. Entonces escribe `players/O = uid`, `presence/O = true`,
   `status = "playing"` y reinicia `board`, `turn="X"`, `round=1`, `score={X:0,O:0}`.
3. Si no existe, está llena o ya empezó → **código no válido**. Si no hay red → error de conexión.

**Jugar una casilla** (transacción sobre `rooms/{code}`)
- Solo si `status="playing"`, `turn` es mi marca, `players/{mi marca}` es mi uid y `board[i] == "0"`.
- Escribe `board[i] = mi marca`. Después:
  - si hay 3 en línea → `status="finished"`, `winner=mi marca`, `score/{mi marca} += 1`;
  - si el tablero está lleno → `status="finished"`, `winner="draw"`;
  - si no → `turn` pasa al otro jugador.

**Play again** (transacción, solo si `status="finished"`)
- `round += 1`, `board="000000000"`, `status="playing"`, se borra `winner`, y `turn` alterna quién abre:
  `X` si `round` es impar, `O` si es par. Si los dos pulsan a la vez no pasa nada raro: la segunda
  transacción ve `status="playing"` y se aborta.

**Presencia**
- Al conectarse cada jugador registra `presence/{marca}.onDisconnect().setValue(false)` y luego escribe
  `presence/{marca} = true`. Debe repetirlo cuando `.info/connected` vuelva a ser `true`.
- Si `presence` del rival es `false`, la app debe mostrar "rival desconectado" y no dejar jugar.

**Salir**
- Anfitrión: borra `rooms/{code}` (el invitado detecta que desapareció).
- Invitado: pone `players/O` y `presence/O` en `null` y `status="waiting"` (la sala vuelve a esperar rival).

## 4. Seguridad (reglas)

`firebase/database.rules.json` hace lo siguiente:

- Solo usuarios autenticados leen y escriben, y solo en `rooms/{4 dígitos}`.
- Solo el creador puede crear la sala y solo él puede borrarla.
- Solo se puede entrar como invitado a una sala `waiting` sin `players/O`.
- Un jugador solo puede cambiar el tablero cuando es su turno (o la partida terminó, para reiniciar).
- Valida tipos y formato (`board` = 9 dígitos 0-2, `status`, `turn`, `winner`, etc.).

Limitaciones conocidas (aceptables para jugar entre amigos):

- Las reglas no comprueban que la jugada sea legal (una app tramposa podría sobrescribir una casilla en su
  turno). Para evitarlo del todo habría que validar jugadas en una Cloud Function.
- Un código de 4 dígitos es fácil de adivinar (10 000 combinaciones). Para algo más serio, usar un código
  más largo o Firebase App Check.
- Las salas abandonadas no se borran solas; su código se reutiliza pasadas 6 h. Se puede limpiar con una
  Cloud Function programada.

## 5. Checklist para la app de tu compañero

- [ ] Autenticación anónima antes de tocar la base.
- [ ] Mismos nombres de campo y mismos valores (`"X"`, `"O"`, `"draw"`, `"waiting"`, ...).
- [ ] Tablero como texto de 9 caracteres `0/1/2`.
- [ ] Jugadas, unirse y Play again con **transacciones**.
- [ ] Presencia con `onDisconnect`.
- [ ] Mostrar "código no válido" cuando la sala no exista, esté llena o ya empezó.
