**Quality attribute scenario**: Responsiveness

**Feature**: High throughput in case of overload

_when_ initiate 50 concurrent requests \
_caused by_ 50 users \
_occur in_ the system \
_operating in_ normal operation \
_then_ the system processes all requests \
_so that_ the throughput is >= 40 requests/second


**Quality attribute scenario**: Availability

**Feature**: High availability in case of overload

_when_ initiate 50 concurrent requests \
_caused by_ 50 users \
_occur in_ the system \
_operating in_ normal operation \
_then_ the system processes all requests \
_so that_ at least the 99% of the requests should succeed


**Quality attribute scenario**: Handling partial failures

**Feature**: Avoid pointless requests in case of unavailability of account service

_when_ more than 50% of total requests fail \
_caused by_ unavailability of account service \
_occur in_ the system \
_operating in_ normal operation \
_then_ the system makes the requests fail immediately without propagating to the account service \
_so that_ it lightens the workload avoiding pointless requests
